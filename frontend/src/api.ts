export type Perfil = 'ADMINISTRACION' | 'PERSONAL' | 'CLIENTE';
export interface Sesion {
  usuario: { id: number; nombre: string; correo: string; perfil: Perfil };
  iniciadaEn: string;
  limiteAbsoluto: string;
  vencePorInactividadEn: string;
}
interface Csrf { headerName: string; token: string }
let activity: (()=>void)|null=null;
export function onActivity(handler:()=>void) {activity=handler;return()=>{if(activity===handler)activity=null;};}
let token: Csrf | null = null;
let pendingToken: Promise<Csrf> | null = null;

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message); }
}

async function read<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    throw new ApiError(response.status, data.code ?? 'ERROR',
      data.title ?? 'No se pudo completar la solicitud. Intentá nuevamente.');
  }
  activity?.();
  return response.status === 204 ? undefined as T : response.json();
}
export function clearCsrf() { token = null; pendingToken = null; }
export async function csrf(): Promise<Csrf> {
  if (token) return token;
  if (!pendingToken) {
    pendingToken = fetch('/api/auth/csrf', { credentials: 'same-origin', cache: 'no-store' })
      .then(read<Csrf>).then(value => { token = value; return value; })
      .finally(() => { pendingToken = null; });
  }
  return pendingToken;
}
export async function get<T>(path: string): Promise<T> {
  return fetch(path, { credentials: 'same-origin', cache: 'no-store' }).then(read<T>);
}
export async function post<T>(path: string, body?: unknown, retry = true): Promise<T> {
  const csrfToken = await csrf();
  const response = await fetch(path, {
    method: 'POST', credentials: 'same-origin',
    headers: { [csrfToken.headerName]: csrfToken.token, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  try { return await read<T>(response); }
  catch (error) {
    // CSRF rejection occurs before the controller; refreshing and retrying once is safe.
    if (retry && error instanceof ApiError && error.code === 'CSRF_INVALIDO') {
      clearCsrf();
      return post<T>(path, body, false);
    }
    throw error;
  }
}
export async function login(correo: string, contrasena: string): Promise<Sesion> {
  const result = await post<Sesion>('/api/auth/login', { correo: correo.trim(), contrasena });
  clearCsrf();
  return result;
}
export async function logout(): Promise<void> {
  await post<void>('/api/auth/logout');
  clearCsrf();
}
export const homePath = (perfil: Perfil) => '/api/' +
  ({ ADMINISTRACION: 'administracion', PERSONAL: 'personal', CLIENTE: 'cliente' }[perfil]) + '/inicio';

export async function pdf(path:string):Promise<Blob>{
  const response=await fetch(path,{credentials:'same-origin',cache:'no-store',headers:{Accept:'application/pdf'}});
  if(!response.ok){await read<never>(response);throw new Error('No se pudo descargar el PDF.');}
  if(!response.headers.get('Content-Type')?.toLowerCase().startsWith('application/pdf'))throw new Error('Respuesta de documento inválida.');
  const result=await response.blob();activity?.();return result;
}
