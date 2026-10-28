import { collector } from '../collector/index.js';
import { runCase } from './run-case/index.js';
export async function run() {
    collector.close();
    return JSON.stringify(await collector
        .cases()
        .reduce(async (before, one) => [
        ...(await before),
        await runCase(collector, one),
    ], Promise.resolve([])));
}

