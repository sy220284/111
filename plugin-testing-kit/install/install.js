import { installed } from '../installed/index.js';
export function install(host, eventsText) {
    const events = JSON.parse(eventsText);
    installed.install({ host, events });
}

