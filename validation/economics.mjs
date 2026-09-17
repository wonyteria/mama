import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

function number(name, value, max = Infinity) {
  if (!Number.isFinite(value) || value < 0 || value > max) {
    throw new RangeError(`${name} must be finite and within 0..${max}`);
  }
  return value;
}

export function economics(s) {
  for (const key of ['priceKrw', 'taxRate', 'calls', 'inputTokens', 'outputTokens',
    'inputUsdPerMillion', 'outputUsdPerMillion', 'fxKrwPerUsd', 'infraKrw',
    'supportMinutes', 'supportHourlyKrw', 'fixedMonthlyKrw', 'paidCacKrw']) {
    number(key, s[key]);
  }
  for (const key of ['storeFeeRate', 'refundRate', 'monthlyChurn']) number(key, s[key], 1);
  const proceeds = s.priceKrw / (1 + s.taxRate) * (1 - s.storeFeeRate) * (1 - s.refundRate);
  const modelCost = s.calls * (s.inputTokens * s.inputUsdPerMillion +
    s.outputTokens * s.outputUsdPerMillion) / 1e6 * s.fxKrwPerUsd;
  const supportCost = s.supportMinutes / 60 * s.supportHourlyKrw;
  const contribution = proceeds - modelCost - s.infraKrw - supportCost;
  let survival = 1;
  let cumulative = 0;
  let value12 = 0;
  let paybackMonths = s.paidCacKrw === 0 && contribution >= 0 ? 0 : null;
  for (let month = 1; month <= 24; month++) {
    cumulative += contribution * survival;
    if (month === 12) value12 = cumulative;
    if (paybackMonths === null && contribution > 0 && cumulative >= s.paidCacKrw) {
      paybackMonths = month;
    }
    survival *= 1 - s.monthlyChurn;
  }
  return {
    name: s.name, priceKrw: s.priceKrw, proceedsKrw: proceeds,
    modelCostKrw: modelCost, supportCostKrw: supportCost,
    contributionKrw: contribution,
    breakEvenPaidHouseholds: contribution > 0 ? Math.ceil(s.fixedMonthlyKrw / contribution) : null,
    contribution12MonthsKrw: value12,
    contribution12MonthsAfterCacKrw: value12 - s.paidCacKrw,
    paybackMonthsWithin24: paybackMonths,
  };
}

export function minimumPaidShare(paidContribution, freeCost) {
  number('freeCost', freeCost);
  if (!Number.isFinite(paidContribution)) throw new RangeError('Invalid contribution');
  if (paidContribution <= 0) return null;
  return freeCost / (paidContribution + freeCost);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const config = JSON.parse(readFileSync(new URL('./economics-assumptions.json', import.meta.url), 'utf8'));
  const results = config.scenarios.flatMap(s => config.pricesKrw.map(priceKrw =>
    economics({ ...config.common, ...s, priceKrw })));
  const output = {
    status: 'ILLUSTRATIVE_SCENARIOS_NOT_OBSERVED_BUSINESS_RESULTS',
    generatedAt: new Date().toISOString(),
    assumptions: config,
    results,
    freeCostSensitivity: [300, 800].map(freeCostKrw => ({
      freeCostKrw, minimumPaidShare: minimumPaidShare(results.find(r =>
        r.name === 'base' && r.priceKrw === 4900).contributionKrw, freeCostKrw),
    })),
  };
  writeFileSync(new URL('./economics-results.json', import.meta.url), JSON.stringify(output, null, 2) + '\n');
  console.table(results.map(r => ({
    scenario: r.name, price: r.priceKrw, aiCost: Math.round(r.modelCostKrw),
    supportCost: Math.round(r.supportCostKrw), contribution: Math.round(r.contributionKrw),
    fixedCostBreakEven: r.breakEvenPaidHouseholds,
    paybackMonths: r.paybackMonthsWithin24,
  })));
}
