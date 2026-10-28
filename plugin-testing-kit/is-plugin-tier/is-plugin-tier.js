import { PLUGIN_TIERS } from '../plugin-tiers/index.js';
export const isPluginTier = (value) => PLUGIN_TIERS.some(tier => tier === value);

