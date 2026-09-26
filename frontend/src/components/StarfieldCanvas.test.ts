import { mount } from '@vue/test-utils';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import StarfieldCanvas from './StarfieldCanvas.vue';

const fakeCtx = () => ({
  setTransform: vi.fn(),
  clearRect: vi.fn(),
  fillRect: vi.fn(),
  fillStyle: '',
  globalAlpha: 1,
});
const stubMatchMedia = (reduced: boolean) =>
  vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: reduced }));

beforeEach(() =>
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(fakeCtx() as never),
);
afterEach(() => vi.unstubAllGlobals());

it('renders an aria-hidden canvas and animates when motion is allowed', () => {
  stubMatchMedia(false);
  const raf = vi.spyOn(window, 'requestAnimationFrame').mockReturnValue(1 as never);
  const node = mount(StarfieldCanvas);
  expect(node.get('canvas.starfield').attributes('aria-hidden')).toBe('true');
  expect(raf).toHaveBeenCalled();
});

it('paints a static field without a rAF loop under reduced motion', () => {
  stubMatchMedia(true);
  const raf = vi.spyOn(window, 'requestAnimationFrame');
  mount(StarfieldCanvas);
  expect(raf).not.toHaveBeenCalled();
});

it('cancels the frame on unmount', () => {
  stubMatchMedia(false);
  vi.spyOn(window, 'requestAnimationFrame').mockReturnValue(7 as never);
  const cancel = vi.spyOn(window, 'cancelAnimationFrame');
  mount(StarfieldCanvas).unmount();
  expect(cancel).toHaveBeenCalledWith(7);
});
