import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import GalaxyVine from './GalaxyVine.vue';

it('is decorative and binds growth to scroll progress', () => {
  const node = mount(GalaxyVine, { props: { progress: 0.25, nodes: [] } });
  expect(node.get('svg.galaxy-vine').attributes('aria-hidden')).toBe('true');
  expect(node.get('path.vine-path').attributes('style')).toContain('stroke-dashoffset: 0.75');
});

it('lights nodes the growing vine has already reached', () => {
  const node = mount(GalaxyVine, { props: { progress: 0.5, nodes: [0.2, 0.8] } });
  const dots = node.findAll('circle.vine-node');
  expect(dots).toHaveLength(2);
  expect(dots[0].classes()).toContain('lit');
  expect(dots[1].classes()).not.toContain('lit');
});
