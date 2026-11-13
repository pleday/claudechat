import { execFile } from 'node:child_process';
import * as fs from 'node:fs';
import * as path from 'node:path';

export interface VideoInfo { durationSec: number; width: number; height: number; hasAudio: boolean }

/**
 * Pièces jointes vidéo. L'API n'accepte pas la vidéo : on en extrait quelques images (ffmpeg) envoyées comme images,
 * accompagnées d'un texte qui donne les instants. La piste audio n'est pas transmise.
 */
export const VIDEO_EXTENSIONS = new Set(['mp4', 'mov', 'm4v', 'webm', 'mkv', 'avi', 'mpg', 'mpeg', 'wmv', 'flv', '3gp']);
export const MAX_FRAMES = 10;
export const MAX_WIDTH = 1024;

export const isVideo = (fileName: string) => VIDEO_EXTENSIONS.has(fileName.split('.').pop()?.toLowerCase() ?? '');

/** Cherche un exécutable dans le PATH puis dans les emplacements usuels. */
export function findExecutable(name: string): string | undefined {
  const dirs = [...(process.env.PATH ?? '').split(path.delimiter), '/usr/bin', '/usr/local/bin', '/opt/homebrew/bin', '/snap/bin'];
  const exts = process.platform === 'win32' ? ['.exe', '.cmd', ''] : [''];
  for (const d of dirs) for (const e of exts) {
    if (!d) continue;
    const f = path.join(d, name + e);
    try { fs.accessSync(f, fs.constants.X_OK); if (fs.statSync(f).isFile()) return f; } catch { /* suivant */ }
  }
  return undefined;
}

const run = (cmd: string, args: string[], timeoutMs = 30_000): Promise<Buffer | undefined> =>
  new Promise((resolve) => {
    execFile(cmd, args, { encoding: 'buffer', timeout: timeoutMs, maxBuffer: 64 * 1024 * 1024 }, (err, stdout) => resolve(err ? undefined : stdout));
  });

export async function probe(file: string, ffprobe: string): Promise<VideoInfo | undefined> {
  const out = await run(ffprobe, ['-v', 'error', '-print_format', 'json', '-show_format', '-show_streams', file]);
  if (!out) return undefined;
  try {
    const j = JSON.parse(out.toString('utf8'));
    const streams: any[] = Array.isArray(j.streams) ? j.streams : [];
    const v = streams.find((s) => s.codec_type === 'video');
    if (!v) return undefined;
    const dur = Number.parseFloat(j.format?.duration ?? v.duration ?? '0') || 0;
    return { durationSec: dur, width: v.width ?? 0, height: v.height ?? 0, hasAudio: streams.some((s) => s.codec_type === 'audio') };
  } catch { return undefined; }
}

/** Instants d'extraction : au milieu de segments égaux (au plus MAX_FRAMES, environ une image toutes les 3 s). */
export function frameTimes(durationSec: number): number[] {
  if (durationSec < 1) return [0];
  const n = Math.min(MAX_FRAMES, Math.max(2, Math.ceil(durationSec / 3)));
  return Array.from({ length: n }, (_, i) => ((i + 0.5) * durationSec) / n);
}

/** Une image JPEG (réduite à MAX_WIDTH de large) à l'instant donné ; undefined si l'extraction échoue. */
export async function frameAt(file: string, seconds: number, ffmpeg: string): Promise<Buffer | undefined> {
  const out = await run(ffmpeg, ['-v', 'error', '-ss', seconds.toFixed(3), '-i', file, '-frames:v', '1',
    '-vf', `scale='min(${MAX_WIDTH},iw)':-2`, '-q:v', '4', '-f', 'image2', '-c:v', 'mjpeg', 'pipe:1']);
  return out && out.length > 2 && out[0] === 0xff && out[1] === 0xd8 ? out : undefined;
}

export const fmt = (sec: number) => `${Math.floor(sec / 60)}:${String(Math.floor(sec % 60)).padStart(2, '0')}`;

export const describe = (rel: string, info: VideoInfo, times: number[]): string =>
  `<video path="${rel}" duration="${fmt(info.durationSec)}" resolution="${info.width}x${info.height}" frames="${times.length}" at="${times.map(fmt).join(', ')}">` +
  "Les images jointes sont extraites de cette vidéo aux instants indiqués, dans l'ordre." +
  (info.hasAudio ? " La piste audio n'est pas transmise." : '') + '</video>';

export const unavailable = (rel: string, reason: string): string =>
  `<video path="${rel}">Vidéo non analysée : ${reason}. Tu peux en extraire des images toi-même avec ffmpeg (outil Bash).</video>`;

/** Extrait les images et le texte descriptif ; en cas d'impossibilité, un texte explicatif sans image. */
export async function attachVideo(file: string, rel: string, onProgress: (i: number, n: number) => void = () => {}): Promise<{ text: string; frames: Buffer[] }> {
  const ffmpeg = findExecutable('ffmpeg'); const ffprobe = findExecutable('ffprobe');
  if (!ffmpeg || !ffprobe) return { text: unavailable(rel, 'ffmpeg est introuvable sur cette machine'), frames: [] };
  const info = await probe(file, ffprobe);
  if (!info) return { text: unavailable(rel, 'fichier vidéo illisible'), frames: [] };
  const times = frameTimes(info.durationSec);
  const frames: Buffer[] = []; const kept: number[] = [];
  for (const [i, t] of times.entries()) {
    onProgress(i, times.length);
    const f = await frameAt(file, t, ffmpeg);
    if (f) { frames.push(f); kept.push(t); }
  }
  if (!frames.length) return { text: unavailable(rel, "aucune image n'a pu être extraite"), frames: [] };
  return { text: describe(rel, info, kept), frames };
}

