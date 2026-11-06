import { TourStepDto } from '../../api/models/tour-step-dto';
import { TourTargetDto } from '../../api/models/tour-target-dto';
import { excerptMarks } from './excerpt';

/** A five-line file: two metadata groups, a summary header and two summary rows. */
const FILE = [
  'MTD\tmzTab-version\t2.0.0-M',
  'MTD\tassay[1]-sample_ref\tsample[1]',
  'SMH\tSML_ID\tchemical_name',
  'SML\t1\tCer',
  'SML\t2\tPC',
];

/** The server sends null for a step without a target. */
const step = (target: TourTargetDto | null, lines = [1, 5]): TourStepDto => ({
  id: 's',
  chapter: 'c',
  title: 't',
  text: 'x',
  lines,
  target: target as TourTargetDto,
});

describe('excerptMarks', () => {
  it('marks the line of a metadata key', () => {
    expect(excerptMarks(FILE, step({ tab: 'metadata', key: 'assay[1]-sample_ref' }))).toEqual(
      new Set([2]),
    );
  });

  it('marks every line of a metadata group', () => {
    expect(excerptMarks(FILE, step({ tab: 'metadata', group: 'assay' }))).toEqual(new Set([2]));
  });

  it('puts keys without a bracket into the group general', () => {
    expect(excerptMarks(FILE, step({ tab: 'metadata', group: 'general' }))).toEqual(new Set([1]));
  });

  it('marks the data row by its index among the rows of the section', () => {
    expect(excerptMarks(FILE, step({ tab: 'summary', rowIndex: 1 }))).toEqual(new Set([5]));
    expect(excerptMarks(FILE, step({ tab: 'summary', rowIndex: 0, column: 'chemical_name' }))).toEqual(
      new Set([4]),
    );
  });

  it('marks the header line for a header column', () => {
    expect(excerptMarks(FILE, step({ tab: 'summary', column: 'SML_ID', header: true }))).toEqual(
      new Set([3]),
    );
  });

  it('marks nothing without a target', () => {
    expect(excerptMarks(FILE, step(null))).toEqual(new Set());
  });

  it('marks nothing for a row that does not exist', () => {
    expect(excerptMarks(FILE, step({ tab: 'summary', rowIndex: 7 }))).toEqual(new Set());
  });

  it('staysInsideTheStepLines', () => {
    // group "assay" is line 2 and the second row is line 5: both outside the range 3-4
    expect(excerptMarks(FILE, step({ tab: 'metadata', group: 'assay' }, [3, 4]))).toEqual(new Set());
    expect(excerptMarks(FILE, step({ tab: 'summary', rowIndex: 1 }, [3, 4]))).toEqual(new Set());
    expect(excerptMarks(FILE, step({ tab: 'summary', rowIndex: 0 }, [3, 4]))).toEqual(new Set([4]));
  });
});
