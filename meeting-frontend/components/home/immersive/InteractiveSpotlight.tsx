'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  motion,
  useSpring,
  useTransform,
  type MotionValue,
  type SpringOptions,
} from 'framer-motion';
import styles from '@/styles/HomeImmersive.module.css';

type InteractiveSpotlightProps = {
  size?: number;
  springOptions?: SpringOptions;
  fill?: string;
  trackPointerGlobally?: boolean;
  activation?: MotionValue<number>;
};

export function InteractiveSpotlight({
  size = 200,
  springOptions = { bounce: 0 },
  fill = 'white',
  trackPointerGlobally = false,
  activation,
}: InteractiveSpotlightProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [isHovered, setIsHovered] = useState(false);
  const [parentElement, setParentElement] = useState<HTMLElement | null>(null);
  const mouseX = useSpring(0, springOptions);
  const mouseY = useSpring(0, springOptions);
  const spotlightLeft = useTransform(mouseX, (x) => `${x - size / 2}px`);
  const spotlightTop = useTransform(mouseY, (y) => `${y - size / 2}px`);

  useEffect(() => {
    const parent = containerRef.current?.parentElement;
    if (!parent) return;

    const previousPosition = parent.style.position;
    const previousOverflow = parent.style.overflow;
    parent.style.position = 'relative';
    parent.style.overflow = 'hidden';
    setParentElement(parent);

    return () => {
      parent.style.position = previousPosition;
      parent.style.overflow = previousOverflow;
    };
  }, []);

  const handleMouseMove = useCallback(
    (event: MouseEvent) => {
      if (!parentElement) return;
      const { left, right, top, bottom } = parentElement.getBoundingClientRect();
      mouseX.set(event.clientX - left);
      mouseY.set(event.clientY - top);

      if (trackPointerGlobally) {
        setIsHovered(
          event.clientX >= left &&
            event.clientX <= right &&
            event.clientY >= top &&
            event.clientY <= bottom,
        );
      }
    },
    [mouseX, mouseY, parentElement, trackPointerGlobally],
  );

  useEffect(() => {
    if (!parentElement) return;

    const handleMouseEnter = () => setIsHovered(true);
    const handleMouseLeave = () => setIsHovered(false);
    let listening = false;

    const addListeners = () => {
      if (listening) return;
      listening = true;

      if (trackPointerGlobally) {
        window.addEventListener('mousemove', handleMouseMove, { passive: true });
        return;
      }

      parentElement.addEventListener('mousemove', handleMouseMove);
      parentElement.addEventListener('mouseenter', handleMouseEnter);
      parentElement.addEventListener('mouseleave', handleMouseLeave);
    };

    const removeListeners = () => {
      if (!listening) return;
      listening = false;

      if (trackPointerGlobally) {
        window.removeEventListener('mousemove', handleMouseMove);
        return;
      }

      parentElement.removeEventListener('mousemove', handleMouseMove);
      parentElement.removeEventListener('mouseenter', handleMouseEnter);
      parentElement.removeEventListener('mouseleave', handleMouseLeave);
    };

    const updateActivation = (value: number) => {
      if (value > 0.01) addListeners();
      else if (listening) {
        removeListeners();
        setIsHovered(false);
      }
    };

    if (activation) updateActivation(activation.get());
    else addListeners();

    const unsubscribe = activation?.on('change', updateActivation);

    return () => {
      unsubscribe?.();
      removeListeners();
    };
  }, [activation, parentElement, handleMouseMove, trackPointerGlobally]);

  return (
    <motion.div
      ref={containerRef}
      className={`${styles.interactiveSpotlight} ${isHovered ? styles.spotlightVisible : ''}`}
      style={{
        width: size,
        height: size,
        left: spotlightLeft,
        top: spotlightTop,
        background: `radial-gradient(circle at center, ${fill} 0%, rgba(255,255,255,0.35) 28%, rgba(255,255,255,0.08) 52%, transparent 80%)`,
      }}
      aria-hidden="true"
    />
  );
}
