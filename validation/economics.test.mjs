import test from 'node:test';
import assert from 'node:assert/strict';
import { economics, minimumPaidShare } from './economics.mjs';

const simple = {
  name: 'reference', priceKrw: 1000, taxRate: 0, storeFeeRate: 0.1,
  refundRate: 0, calls: 1000, inputTokens: 1000, outputTokens: 100,
  inputUsdPerMillion: 1, outputUsdPerMillion: 2, fxKrwPerUsd: 100,
  infraKrw: 30, supportMinutes: 1, supportHourlyKrw: 3000,
  fixedMonthlyKrw: 1400, paidCacKrw: 1400, monthlyChurn: 0,
};

test('hand-calculated monthly economics and unrounded break even', () => {
  const r = economics(simple);
  assert.equal(r.proceedsKrw, 900);
  assert.equal(r.modelCostKrw, 120);
  assert.equal(r.supportCostKrw, 50);
  assert.equal(r.contributionKrw, 700);
  assert.equal(r.breakEvenPaidHouseholds, 2);
  assert.equal(r.paybackMonthsWithin24, 2);
  assert.equal(r.contribution12MonthsKrw, 8400);
});

test('churn reduces cohort value; CAC does not disappear', () => {
  const r = economics({ ...simple, monthlyChurn: 0.5 });
  assert.equal(r.contribution12MonthsKrw, 1400 * (1 - 0.5 ** 12));
  assert.ok(r.contribution12MonthsAfterCacKrw < 0);
  assert.equal(r.paybackMonthsWithin24, null);
});

test('100% churn keeps only first paid month', () => {
  assert.equal(economics({ ...simple, monthlyChurn: 1 }).contribution12MonthsKrw, 700);
});

test('loss making plans have no break-even or positive CAC payback', () => {
  const r = economics({ ...simple, infraKrw: 900 });
  assert.ok(r.contributionKrw < 0);
  assert.equal(r.breakEvenPaidHouseholds, null);
  assert.equal(r.paybackMonthsWithin24, null);
});

test('invalid economic assumptions fail visibly', () => {
  for (const change of [{ calls: -1 }, { monthlyChurn: 1.1 }, { priceKrw: NaN },
    { fxKrwPerUsd: Infinity }, { storeFeeRate: -0.1 }]) {
    assert.throws(() => economics({ ...simple, ...change }), RangeError);
  }
});

test('free users are not free: weighted contribution must cover them', () => {
  assert.equal(minimumPaidShare(700, 300), 0.3);
  assert.equal(minimumPaidShare(700, 0), 0);
  assert.equal(minimumPaidShare(-1, 300), null);
});
