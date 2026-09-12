/** Samples monotonic time, never trusting a long interval after device suspension. */
export class ReadingClock {
  private last: number;
  private activity: number;
  private enabled = false;
  private elapsed = 0;
  constructor(now: number) {
    this.last = now;
    this.activity = now;
  }
  touch(now: number) {
    this.activity = now;
  }
  tick(now: number, active: boolean) {
    const delta = now - this.last;
    if (this.enabled && delta >= 0 && delta <= 5000) {
      this.elapsed += Math.min(delta, Math.max(0, this.activity + 120_000 - this.last));
    }
    this.last = now;
    this.enabled = active && now - this.activity < 120_000;
  }
  get seconds() {
    return Math.floor(this.elapsed / 1000);
  }
  get running() {
    return this.enabled;
  }
}
