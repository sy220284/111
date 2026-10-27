import { mockClock } from './mock-clock/index.js';
import { mockEnv } from './mock-env/index.js';
import { mockSession } from './mock-session/index.js';
import { mockStore } from './mock-store/index.js';
export const mock = {
    clock: mockClock,
    store: mockStore,
    env: mockEnv,
    session: mockSession,
};

