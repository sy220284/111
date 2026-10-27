import { installed } from '../../installed/index.js';
import { openSeat } from '../../open-seat/index.js';
import { errorTextOf } from './error-text-of/index.js';
import { failureOf } from './failure-of/index.js';
import { hookNotesOf } from './hook-notes-of/index.js';
import { timed } from './timed/index.js';
export async function runCase(collector, one) {
    const { now } = installed.handover().host;
    const started = now();
    let thrown;
    let seat;
    try {
        seat = openSeat(one.plugins, collector.fileTier(), one.options);
        const { $, on } = seat;
        await timed(() => one.body($, on), one.timeoutMs);
    }
    catch (error) {
        thrown = errorTextOf(error);
    }
    let end = { told: '' };
    try {
        end = seat ? await seat.close() : end;
    }
    catch (error) {
        thrown ??= errorTextOf(error);
    }
    const { told, failed } = end;
    const failure = thrown ?? failed?.text;
    const isHooks = thrown === undefined && failed !== undefined;
    const notes = isHooks ? hookNotesOf(failed.event, told) : told;
    return {
        title: one.title,
        ms: now() - started,
        failure: failure === undefined ? null : failureOf(failure, notes),
    };
}

