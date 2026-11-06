import { activeSection } from './active-section';

describe('activeSection', () => {
  it('is the last section whose top has passed the threshold', () => {
    expect(
      activeSection(
        [
          { id: 'a', top: -40 },
          { id: 'b', top: 30 },
          { id: 'c', top: 600 },
        ],
        100,
      ),
    ).toBe('b');
  });

  it('is the last section when all have passed the threshold', () => {
    expect(
      activeSection(
        [
          { id: 'a', top: -400 },
          { id: 'b', top: -200 },
          { id: 'c', top: 20 },
        ],
        100,
      ),
    ).toBe('c');
  });

  it('is the first section when none has reached the threshold', () => {
    expect(
      activeSection(
        [
          { id: 'a', top: 200 },
          { id: 'b', top: 400 },
        ],
        100,
      ),
    ).toBe('a');
  });

  it('is null without sections', () => expect(activeSection([], 100)).toBeNull());
});
