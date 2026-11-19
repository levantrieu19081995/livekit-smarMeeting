'use client';

import Spline from '@splinetool/react-spline';
import { Component, useEffect, useRef, useState, type ErrorInfo, type ReactNode } from 'react';
import styles from '@/styles/HomeImmersive.module.css';

export const SPLINE_SCENE_URL = 'https://prod.spline.design/kZDDjO5HuC9GJUM2/scene.splinecode';

type SplineErrorBoundaryProps = {
  children: ReactNode;
  onError: () => void;
};

type SplineErrorBoundaryState = {
  hasError: boolean;
};

class SplineErrorBoundary extends Component<SplineErrorBoundaryProps, SplineErrorBoundaryState> {
  state: SplineErrorBoundaryState = { hasError: false };

  static getDerivedStateFromError(): SplineErrorBoundaryState {
    return { hasError: true };
  }

  componentDidCatch(_error: Error, _info: ErrorInfo) {
    this.props.onError();
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className={`${styles.sceneFallback} ${styles.sceneError}`} role="status">
          Không thể tải không gian 3D.
        </div>
      );
    }

    return this.props.children;
  }
}

export function SplineScene() {
  const [shouldMountScene, setShouldMountScene] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    const mountFrame = window.requestAnimationFrame(() => setShouldMountScene(true));

    return () => {
      mountedRef.current = false;
      window.cancelAnimationFrame(mountFrame);
    };
  }, []);

  const handleLoad = () => {
    if (mountedRef.current) setLoaded(true);
  };

  const handleError = () => {
    if (mountedRef.current) setFailed(true);
  };

  return (
    <div
      className={`${styles.splineShell} ${loaded ? styles.splineLoaded : ''}`}
      aria-busy={!loaded && !failed}
    >
      {!loaded && !failed ? (
        <div className={styles.sceneFallback} role="status" aria-live="polite">
          <span className={styles.splineLoader} aria-label="Đang tải scene 3D" />
        </div>
      ) : null}
      {shouldMountScene ? (
        <SplineErrorBoundary onError={handleError}>
          <Spline
            scene={SPLINE_SCENE_URL}
            onLoad={handleLoad}
            style={{ width: '100%', height: '100%' }}
          />
        </SplineErrorBoundary>
      ) : null}
    </div>
  );
}
