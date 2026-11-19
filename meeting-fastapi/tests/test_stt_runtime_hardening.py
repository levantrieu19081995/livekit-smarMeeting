import asyncio
import json
import threading
import time
from dataclasses import replace
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from app.core.blocking import check_cancelled, run_cancellable_cpu
from app.core.config import Settings
from app.core.runtime_limits import stt_execution_slot
from app.models.transcription import SttProgressCallbackPayload, TranscribeMeetingRequest
from app.services.manifest_transcription_service import ManifestTranscriptionService, _WorkItem
from app.services.openai_stt_provider import OpenAISttProvider, SttProviderUnavailable
from app.services.stt_chunk_receipts import SttChunkReceipts
from app.services.transcription_job_service import TranscriptionJobService
from app.services.transcription_job_store import TranscriptionJobStore
from tests.test_manifest_transcription_service import write_wav
from tests.test_transcription_job_store import queued_record, result
from tests.test_transcription_job_service import FakeManifestTranscriptionService, FakeProgressCallbackClient, FakeCompletionCallbackClient, request


def settings(root, **kwargs):
    return Settings(capture_root_dir=root, STT_SPEECH_REGION_ENABLED=False,
                    STT_RETRY_BASE_SECONDS=0, STT_PROGRESS_CALLBACK_ENABLED=False,
                    STT_COMPLETION_CALLBACK_ENABLED=False, **kwargs)


def work_item(service, root):
    wav = root / "audio.wav"
    write_wav(wav, 1)
    plan = service.audio_chunk_service.plan_chunks(wav)[0]
    return _WorkItem("session-1", {"attendeeId": "a", "displayName": "Speaker"},
                     {"segmentId": "s", "segmentIndex": 1, "filePath": "audio.wav", "startedAt": "2026-10-08T00:00:00Z"}, wav, plan)


class Provider:
    def __init__(self): self.calls = 0
    async def transcribe(self, path):
        self.calls += 1
        return "Provider output"


@pytest.mark.asyncio
async def test_stt_quota_is_shared_across_services(tmp_path):
    active, peak = 0, 0
    release, reached = asyncio.Event(), asyncio.Event()
    class CountProvider:
        async def transcribe(self, path):
            nonlocal active, peak
            active += 1
            peak = max(peak, active)
            if active == 2: reached.set()
            await release.wait()
            active -= 1
            return "text"
    config = settings(tmp_path, STT_CONCURRENCY=2, STT_MAX_RETRIES=0)
    services = [ManifestTranscriptionService(config, CountProvider()) for _ in range(3)]
    item = work_item(services[0], tmp_path)
    tasks = [asyncio.create_task(service._process_item(item)) for service in services for _ in range(2)]
    await asyncio.wait_for(reached.wait(), 2)
    await asyncio.sleep(0.02)
    assert active == peak == 2
    release.set()
    answers = await asyncio.gather(*tasks)
    assert peak == 2
    assert all(answer[0] is not None and answer[1] is None for answer in answers)


@pytest.mark.asyncio
async def test_child_task_cannot_inherit_parent_stt_reservation_and_bypass_quota(tmp_path):
    config = settings(tmp_path, STT_CONCURRENCY=1)
    acquired = asyncio.Event()
    async def child():
        async with stt_execution_slot(config): acquired.set()
    async with stt_execution_slot(config):
        # The same task can nest provider and service reservations safely.
        async with stt_execution_slot(config): pass
        task = asyncio.create_task(child())
        await asyncio.sleep(0.01)
        assert not acquired.is_set()
    await asyncio.wait_for(task, 1)
    assert acquired.is_set()


@pytest.mark.asyncio
async def test_paid_chunk_receipt_survives_service_restart_and_changed_audio_invalidates(tmp_path):
    config = settings(tmp_path, STT_MAX_RETRIES=0)
    original = Provider()
    service = ManifestTranscriptionService(config, original)
    item = work_item(service, tmp_path)
    first, failure = await service._process_item(item, request_id="stable-request")
    assert failure is None and original.calls == 1
    replacement = Provider()
    recovered = ManifestTranscriptionService(config, replacement)
    second, failure = await recovered._process_item(item, request_id="stable-request")
    assert failure is None and second.text == first.text and replacement.calls == 0
    # Alter PCM bytes without changing identities/timestamps.
    with item.wav_path.open("r+b") as handle:
        handle.seek(44)
        handle.write(b"\x01\x00")
    await recovered._process_item(item, request_id="stable-request")
    assert replacement.calls == 1


@pytest.mark.asyncio
async def test_receipt_persistence_retry_never_repeats_known_paid_output(tmp_path, monkeypatch):
    provider = Provider()
    service = ManifestTranscriptionService(settings(tmp_path, STT_MAX_RETRIES=2), provider)
    item = work_item(service, tmp_path)
    original_write = SttChunkReceipts.write
    writes = 0
    def fail_first(self, *args):
        nonlocal writes
        writes += 1
        if writes == 1: raise OSError("temporary disk failure")
        return original_write(self, *args)
    monkeypatch.setattr(SttChunkReceipts, "write", fail_first)
    transcript, failure = await service._process_item(item, request_id="stable-request")
    assert failure is None and transcript.text == "Provider output"
    assert writes == 2 and provider.calls == 1


@pytest.mark.asyncio
async def test_corrupt_paid_receipt_fails_without_provider_call(tmp_path):
    provider = Provider()
    service = ManifestTranscriptionService(settings(tmp_path), provider)
    item = work_item(service, tmp_path)
    chunk = service.audio_chunk_service.materialize_chunk(item.plan)
    receipts = SttChunkReceipts(service.settings)
    path, fingerprint = receipts.path_and_fingerprint("stable-request", item, chunk)
    receipts.write(path, fingerprint, "previous")
    path.write_text("{invalid", encoding="utf-8")
    transcript, failure = await service._process_item(item, request_id="stable-request")
    assert transcript is None and "receipt is invalid" in failure.error
    assert provider.calls == 0


@pytest.mark.asyncio
async def test_unknown_provider_timeout_is_not_retried(tmp_path):
    class TimeoutProvider(Provider):
        async def transcribe(self, path):
            self.calls += 1
            raise TimeoutError("unknown response")
    provider = TimeoutProvider()
    service = ManifestTranscriptionService(settings(tmp_path, STT_MAX_RETRIES=3), provider)
    transcript, failure = await service._process_item(work_item(service, tmp_path))
    assert transcript is None and failure.attempts == 1 and provider.calls == 1
    assert "unknown response" not in failure.error


@pytest.mark.asyncio
async def test_provider_client_is_reused_and_closed_only_when_owned(tmp_path, monkeypatch):
    path = tmp_path / "audio.wav"
    path.write_bytes(b"wav")
    calls, closes, builds = 0, 0, 0
    async def create(**kwargs):
        nonlocal calls
        calls += 1
        return SimpleNamespace(text="text")
    async def close():
        nonlocal closes
        closes += 1
    client = SimpleNamespace(audio=SimpleNamespace(transcriptions=SimpleNamespace(create=create)), close=close)
    def build():
        nonlocal builds
        builds += 1
        return client
    provider = OpenAISttProvider(settings(tmp_path))
    monkeypatch.setattr(provider, "_build_openai_client", build)
    await asyncio.gather(provider.transcribe(path), provider.transcribe(path))
    await provider.close()
    await provider.close()
    assert (calls, builds, closes) == (2, 1, 1)
    injected = OpenAISttProvider(settings(tmp_path), client)
    await injected.close()
    assert closes == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("status_code,retryable", [(None, False), (429, True), (503, False), (400, False)])
async def test_provider_errors_are_opaque_and_retry_policy_is_explicit(tmp_path, status_code, retryable):
    async def create(**kwargs):
        failure = RuntimeError("secret-provider-key and endpoint")
        if status_code is not None: failure.status_code = status_code
        raise failure
    client = SimpleNamespace(audio=SimpleNamespace(transcriptions=SimpleNamespace(create=create)))
    path = tmp_path / "a.wav"
    path.write_bytes(b"wav")
    provider = OpenAISttProvider(settings(tmp_path), client)
    with pytest.raises(SttProviderUnavailable) as raised: await provider.transcribe(path)
    assert raised.value.detail == "STT provider is unavailable"
    assert raised.value.retryable is retryable


@pytest.mark.asyncio
async def test_cpu_work_is_off_loop_and_cooperatively_cancelled():
    entered, exited = threading.Event(), threading.Event()
    loop_thread = threading.get_ident()
    worker_thread = []
    def calculate():
        worker_thread.append(threading.get_ident())
        entered.set()
        try:
            while True:
                check_cancelled()
                time.sleep(0.002)
        finally: exited.set()
    task = asyncio.create_task(run_cancellable_cpu(calculate))
    await asyncio.to_thread(entered.wait, 1)
    heartbeat = 0
    for _ in range(5):
        await asyncio.sleep(0.001)
        heartbeat += 1
    task.cancel()
    with pytest.raises(asyncio.CancelledError): await task
    assert heartbeat == 5 and exited.is_set()
    assert worker_thread != [loop_thread]


@pytest.mark.asyncio
async def test_restart_never_replays_unknown_paid_provider_dispatch(tmp_path):
    provider = Provider()
    service = ManifestTranscriptionService(settings(tmp_path), provider)
    item = work_item(service, tmp_path)
    chunk = service.audio_chunk_service.materialize_chunk(item.plan)
    receipts = SttChunkReceipts(service.settings)
    marker = receipts.path_and_fingerprint("stable-request", item, chunk)
    receipts.reserve(*marker)
    # Process could die after dispatch but before receiving/persisting text.
    transcript, failure = await service._process_item(item, request_id="stable-request")
    assert transcript is None and provider.calls == 0
    assert "outcome is unknown" in failure.error


@pytest.mark.parametrize("failed_directory", ["_status", "_requests"])
def test_primary_job_commit_survives_derived_index_failure_and_restart(tmp_path, monkeypatch, failed_directory):
    store = TranscriptionJobStore(tmp_path)
    original_write = store._atomic_write
    def broken(path, payload):
        if path.parent.name == failed_directory: raise OSError("derived write failed")
        original_write(path, payload)
    monkeypatch.setattr(store, "_atomic_write", broken)
    queued = queued_record()
    store.save(queued)
    assert store.find_request(queued.request_id).processing_id == queued.processing_id
    completed = replace(queued, status="COMPLETED", stage="COMPLETED", processed_chunks=1,
                        total_chunks=1, progress_percent=100, result=result())
    store.save(completed)
    assert store.metadata(queued.processing_id).status == "COMPLETED"
    assert store.load(queued.processing_id).result.transcripts[0].text == "hello"
    restarted = TranscriptionJobStore(tmp_path)
    assert restarted.rebuild_indexes() == []
    assert restarted.find_request(queued.request_id).status == "COMPLETED"


@pytest.mark.asyncio
async def test_terminal_cache_eviction_preserves_results_and_idempotent_receipts(tmp_path):
    fake = FakeManifestTranscriptionService()
    service = TranscriptionJobService(fake, settings=settings(tmp_path, STT_TERMINAL_CACHE_SIZE=0))
    receipts = []
    for index in range(5):
        req = request().model_copy(update={"requestId": f"request-{index}"})
        receipt = await service.start_job(req)
        await service._tasks[receipt.processingId]
        assert not service._jobs and not service._request_identities
        receipts.append((req, receipt))
    assert fake.calls == 5
    for req, receipt in receipts:
        assert (await service.start_job(req)).processingId == receipt.processingId
        assert (await service.get_status(receipt.processingId)).status == "COMPLETED"
        assert (await service.get_result(receipt.processingId)).result.transcripts[0].text == "hello"
        assert not service._jobs
    assert fake.calls == 5
    await service.shutdown()


@pytest.mark.asyncio
async def test_pending_job_restart_resumes_original_processing_id(tmp_path):
    original = FakeManifestTranscriptionService(never_finish=True)
    first = TranscriptionJobService(original, settings=settings(tmp_path))
    receipt = await first.start_job(request())
    while original.calls == 0: await asyncio.sleep(0.001)
    await first.shutdown()
    resumed = FakeManifestTranscriptionService()
    second = TranscriptionJobService(resumed, settings=settings(tmp_path))
    duplicate = await second.start_job(request())
    assert duplicate.processingId == receipt.processingId
    await second._tasks[receipt.processingId]
    assert (await second.get_result(receipt.processingId)).result.status == "COMPLETED"
    assert resumed.calls == 1
    await second.shutdown()


@pytest.mark.asyncio
async def test_job_admission_and_process_job_quota_are_bounded(tmp_path):
    active, peak = 0, 0
    entered, release = asyncio.Event(), asyncio.Event()
    class Blocking(FakeManifestTranscriptionService):
        async def transcribe_meeting(self, *args, **kwargs):
            nonlocal active, peak
            active += 1
            peak = max(peak, active)
            if active == 2: entered.set()
            try: await release.wait()
            finally: active -= 1
            return self.result
    service = TranscriptionJobService(Blocking(), settings=settings(tmp_path, STT_MAX_ACTIVE_JOBS=2, STT_MAX_PENDING_JOBS=3))
    for i in range(3): await service.start_job(request().model_copy(update={"requestId": f"r-{i}"}))
    await entered.wait()
    with pytest.raises(HTTPException) as raised:
        await service.start_job(request().model_copy(update={"requestId": "r-overflow"}))
    assert raised.value.status_code == 429 and peak == 2
    assert (await service.get_status(next(reversed(service._tasks)))).status == "QUEUED"
    release.set()
    await asyncio.gather(*list(service._tasks.values()))
    assert peak == 2
    await service.shutdown()


@pytest.mark.asyncio
async def test_progress_callbacks_coalesce_and_outage_keeps_durable_terminal_result(tmp_path):
    progress = FakeProgressCallbackClient(block=True)
    completion = FakeCompletionCallbackClient(error=RuntimeError("outage"))
    config = settings(tmp_path).model_copy(update={"STT_PROGRESS_CALLBACK_ENABLED": True, "STT_COMPLETION_CALLBACK_ENABLED": True})
    service = TranscriptionJobService(FakeManifestTranscriptionService(), progress, completion, settings=config)
    def payload(n): return SttProgressCallbackPayload(meetingId=12, processedChunks=n, totalChunks=200, progressPercent=n / 2)
    service._schedule_progress_callback("pid", payload(0))
    await progress.started.wait()
    for n in range(1, 201): service._schedule_progress_callback("pid", payload(n))
    assert len(service._callback_tasks) == 1 and len(service._latest_progress) == 1
    progress.release.set()
    await asyncio.gather(*list(service._callback_tasks))
    assert len(progress.calls) == 2 and progress.calls[-1][1].processedChunks == 200
    receipt = await service.start_job(request())
    await service._tasks[receipt.processingId]
    await asyncio.gather(*list(service._callback_tasks))
    assert (await service.get_status(receipt.processingId)).status == "COMPLETED"
    assert (await service.get_result(receipt.processingId)).result.status == "COMPLETED"
    await service.shutdown()


@pytest.mark.asyncio
async def test_failed_progress_worker_cancels_blocked_producer_without_deadlock(tmp_path):
    service = ManifestTranscriptionService(settings(tmp_path, STT_CONCURRENCY=1), Provider())
    item = work_item(service, tmp_path)
    count = 0
    async def fail_after_start(*args):
        nonlocal count
        count += 1
        if count > 1: raise RuntimeError("progress persistence failed")
    with pytest.raises(ExceptionGroup):
        await asyncio.wait_for(service._run_work_items(meeting_id=8, work_items=[item] * 20,
                                planning_failures=[], progress_callback=fail_after_start), 2)


@pytest.mark.asyncio
async def test_client_close_failure_does_not_skip_other_owned_clients(tmp_path):
    calls = []
    class FailingProgress(FakeProgressCallbackClient):
        async def close(self):
            calls.append("progress")
            raise RuntimeError("close failure")
    class Completion(FakeCompletionCallbackClient):
        async def close(self): calls.append("completion")
    class Manifest(FakeManifestTranscriptionService):
        async def close(self): calls.append("provider")
    service = TranscriptionJobService(Manifest(), FailingProgress(), Completion(), settings=settings(tmp_path))
    with pytest.raises(RuntimeError, match="close failure"): await service.shutdown()
    assert set(calls) == {"progress", "completion", "provider"}


@pytest.mark.asyncio
async def test_cancellation_drains_prior_job_write_before_failed_receipt(tmp_path):
    entered, release = threading.Event(), threading.Event()
    states = []
    class DelayedStore(TranscriptionJobStore):
        def save(self, job):
            if job.status == "PROCESSING":
                entered.set()
                assert release.wait(2)
            super().save(job)
            states.append(job.status)
    store = DelayedStore(tmp_path / "jobs")
    manifest = FakeManifestTranscriptionService()
    service = TranscriptionJobService(manifest, store=store, settings=settings(tmp_path))
    receipt = await service.start_job(request())
    await asyncio.to_thread(entered.wait, 1)
    runner = service._tasks[receipt.processingId]
    runner.cancel()
    await asyncio.sleep(0.01)
    assert not runner.done() and states == ["QUEUED"]
    release.set()
    with pytest.raises(asyncio.CancelledError): await runner
    assert states == ["QUEUED", "PROCESSING", "FAILED"]
    assert store.load(receipt.processingId).status == "FAILED"
    assert manifest.calls == 0
    await service.shutdown()


@pytest.mark.asyncio
async def test_cancelled_create_response_after_primary_write_can_resume_same_job(tmp_path):
    entered, release = threading.Event(), threading.Event()
    class DelayedStore(TranscriptionJobStore):
        def save(self, job):
            if job.status == "QUEUED":
                entered.set()
                assert release.wait(2)
            super().save(job)
    store = DelayedStore(tmp_path / "jobs")
    manifest = FakeManifestTranscriptionService()
    service = TranscriptionJobService(manifest, store=store, settings=settings(tmp_path))
    caller = asyncio.create_task(service.start_job(request()))
    await asyncio.to_thread(entered.wait, 1)
    caller.cancel()
    await asyncio.sleep(0.01)
    assert not caller.done()
    release.set()
    with pytest.raises(asyncio.CancelledError): await caller
    original_id = store.find_request(request().requestId).processing_id
    receipt = await service.start_job(request())
    assert receipt.processingId == original_id
    await service._tasks[original_id]
    assert manifest.calls == 1
    assert (await service.get_result(original_id)).result.status == "COMPLETED"
    await service.shutdown()
