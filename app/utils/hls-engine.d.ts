export function createEngine(host: HTMLElement, emit: (event: Record<string, unknown>) => void): { update(options: Record<string, unknown>): void; destroy(): void }
