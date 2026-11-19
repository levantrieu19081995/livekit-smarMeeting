'use client';

import type { ReactNode } from 'react';
import { motion, type MotionValue } from 'framer-motion';
import styles from '@/styles/HomeImmersive.module.css';
import { InteractiveSpotlight } from './InteractiveSpotlight';

type ImmersiveFinalProps = {
  presentationOpacity: MotionValue<number>;
  titleOpacity: MotionValue<number>;
  descriptionOpacity: MotionValue<number>;
  sceneSlot: ReactNode;
};

export function ImmersiveFinal({
  presentationOpacity,
  titleOpacity,
  descriptionOpacity,
  sceneSlot,
}: ImmersiveFinalProps) {
  return (
    <section className={styles.finalDemo} aria-label="Giới thiệu SmartMeeting CUSC">
      <motion.div className={styles.finalPresentation} style={{ opacity: presentationOpacity }}>
        <div className={styles.finalCard}>
          <InteractiveSpotlight size={280} trackPointerGlobally activation={presentationOpacity} />
          <div className={styles.finalLayout}>
            <div className={styles.finalCopy}>
              <motion.h2 className={styles.finalTitle} style={{ opacity: titleOpacity }}>
                SmartMeeting CUSC
              </motion.h2>
              <motion.p className={styles.finalDescription} style={{ opacity: descriptionOpacity }}>
                SmartMeeting CUSC là nền tảng hỗ trợ tổ chức, tham gia và quản lý cuộc họp trực
                tuyến một cách đơn giản, hiện đại và hiệu quả. Hệ thống giúp kết nối mọi người nhanh
                chóng, tăng cường bảo mật dữ liệu cuộc họp, đồng thời hỗ trợ ghi âm, chuyển giọng
                nói thành văn bản và tạo biên bản thông minh, mang đến trải nghiệm họp trực tuyến
                thuận tiện và chuyên nghiệp hơn.
              </motion.p>
            </div>
            <div className={styles.finalInlineScene} aria-hidden="true" />
          </div>
        </div>
      </motion.div>
      <div className={styles.finalSceneSlot}>{sceneSlot}</div>
    </section>
  );
}
