import { describe, expect, it } from 'vitest';
import { ReadingClock } from './reading-clock';

describe('visible, active reading time', () => {
  it('counts foreground reading, not paused, hidden or suspended intervals', () => {
    const clock = new ReadingClock(0);
    clock.tick(0, true);
    clock.tick(1000, true);
    clock.tick(2000, false);
    clock.tick(62_000, true);
    clock.tick(63_000, true);
    expect(clock.seconds).toBe(3);
    clock.tick(83_000, true);
    expect(clock.seconds).toBe(3);
  });
  it('stops after two minutes without interaction and resumes without backfilling', () => {
    const clock = new ReadingClock(0);
    clock.tick(0, true);
    for (let i = 1; i <= 180; i++) clock.tick(i * 1000, true);
    expect(clock.seconds).toBe(120);
    expect(clock.running).toBe(false);
    clock.touch(180_000);
    clock.tick(180_000, true);
    clock.tick(181_000, true);
    expect(clock.seconds).toBe(121);
  });
});
