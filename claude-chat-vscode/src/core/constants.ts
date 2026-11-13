import data from '../../../shared/data/constants.json';

// Constantes et textes communs aux deux éditeurs : ../shared/data/constants.json (une seule source).
export const PERMISSION_MODES: ReadonlyArray<readonly [string, string, string]> = data.permissionModes.map((m) => [m.id, m.label, m.help] as const);
export const EFFORTS: string[] = data.efforts;
export const FALLBACK_MODELS: Array<{ id: string; alias: string; name: string }> = data.fallbackModels;
export const DEFAULT_MODEL: string = data.defaultModel;
export const QUOTA_PROBE_MODEL: string = data.quotaProbeModel;
export const SLASH_PROMPTS: Record<string, string> = data.slashPrompts;
export const COMMIT_PROMPT: string = data.commitPrompt;
export const GENERATED_DIRS = new Set<string>(data.generatedDirs);
export const VIDEO_EXTENSIONS = new Set<string>(data.videoExtensions);
export const LIMITS = data.limits;
