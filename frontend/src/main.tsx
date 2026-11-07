import { StrictMode, useCallback, useEffect, useState, type FormEvent } from 'react';
import { createRoot } from 'react-dom/client';
import * as api from './api';
import type { Perfil, Sesion } from './api';
import './styles.css';
import './operacion.css';
import { Operacion } from './operacion';
import { Resultados } from './resultados';

const names: Record<Perfil, string> = {
  ADMINISTRACION: 'Administración', PERSONAL: 'Personal del taller', CLIENTE: 'Cliente',
};
type Page = string;
const currentPage = (): Page => {
  const path=location.pathname;
  return ['/ingresar','/inicio','/cuenta','/estado','/vehiculos','/ordenes','/ordenes/nueva','/resultados'].includes(path)||/^\/(vehiculos|ordenes)\/[1-9]\d*$/.test(path) ? path+location.search : '/inicio';
};
const hour = (date: string) => new Intl.DateTimeFormat('es-AR', {
  timeZone: 'America/Argentina/Buenos_Aires', hour: '2-digit', minute: '2-digit',
}).format(new Date(date));
const message = (error: unknown) => error instanceof api.ApiError ? error.message :
  'No pudimos conectar con el servicio. Volvé a intentarlo.';

function App() {
  const [page, setPage] = useState<Page>(currentPage);
  const [session, setSession] = useState<Sesion | null>(null);
  const [loading, setLoading] = useState(true);
  const [bootError, setBootError] = useState('');
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const navigate = useCallback((next: Page, replace = false) => {
    if (location.pathname !== next) history[replace ? 'replaceState' : 'pushState'](null, '', next);
    setPage(next);
    setError('');
  }, []);
  const expired = useCallback(() => {
    setSession(null);
    api.clearCsrf();
    setNotice('Tu sesión terminó. Volvé a ingresar para continuar.');
    navigate('/ingresar', true);
  }, [navigate]);

  useEffect(() => {
    const listener = () => setPage(currentPage());
    addEventListener('popstate', listener);
    return () => removeEventListener('popstate', listener);
  }, []);

  const boot = useCallback(async () => {
    setLoading(true); setBootError(''); setError('');
    try {
      const value = await api.get<Sesion>('/api/auth/me');
      setSession(value);
      if (currentPage() === '/ingresar' || location.pathname === '/') navigate('/inicio', true);
    } catch (e) {
      if (e instanceof api.ApiError && e.status === 401) {
        setSession(null);
        if (currentPage() !== '/estado') navigate('/ingresar', true);
      } else setBootError(message(e));
    } finally { setLoading(false); }
  }, [navigate]);
  useEffect(() => { void boot(); }, [boot]);

  useEffect(() => {
    if (!session) return;
    const until = Math.min(Date.parse(session.vencePorInactividadEn), Date.parse(session.limiteAbsoluto));
    const timer = setTimeout(expired, Math.max(0, until - Date.now()));
    return () => clearTimeout(timer);
  }, [session, expired]);

  useEffect(()=>api.onActivity(()=>setSession(previous=>previous?{...previous,
    vencePorInactividadEn:new Date(Date.now()+20*60*1000).toISOString()}:previous)),[]);

  const id = session?.usuario.id;
  const perfil = session?.usuario.perfil;
  useEffect(() => {
    if (!perfil || !id || (page !== '/inicio' && page !== '/cuenta')) return;
    let alive = true;
    setError('');
    api.get<Sesion>(page === '/cuenta' ? '/api/auth/me' : api.homePath(perfil))
      .then(value => { if (alive) setSession(value); })
      .catch(e => {
        if (!alive) return;
        if (e instanceof api.ApiError && e.status === 401) expired();
        else setError(message(e));
      });
    return () => { alive = false; };
  }, [page, id, perfil, expired]);

  async function signIn(correo: string, contrasena: string) {
    setBusy(true); setError(''); setNotice('');
    try {
      const value = await api.login(correo, contrasena);
      setSession(value);
      navigate('/inicio', true);
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function signOut() {
    setBusy(true); setError('');
    try {
      await api.logout();
      setSession(null); setNotice('Cerraste tu sesión.');
      navigate('/ingresar', true);
    } catch (e) {
      if (e instanceof api.ApiError && e.status === 401) expired();
      else setError(message(e));
    } finally { setBusy(false); }
  }
  function link(target: Page, label: string, className = '') {
    return <a className={className} href={target} aria-current={page.split('?')[0]===target||((target==='/vehiculos'||target==='/ordenes')&&page.startsWith(target+'/')) ? 'page' : undefined}
      onClick={event => { event.preventDefault(); navigate(target); }}>{label}</a>;
  }

  return <div className="shell">
    <header className="topbar">
      <a className="brand" href={session ? '/inicio' : '/ingresar'}
        onClick={e => { e.preventDefault(); navigate(session ? '/inicio' : '/ingresar'); }}>
        <span className="mark" aria-hidden="true">T</span><span>Taller<span className="brand-sub">Gestión de órdenes</span></span>
      </a>
      {session && <div className="header-account"><span>{names[session.usuario.perfil]}</span>
        <button className="quiet" onClick={() => void signOut()} disabled={busy}>Cerrar sesión</button></div>}
    </header>
    {loading ? <main className="center"><p role="status">Comprobando tu sesión…</p></main> :
      bootError ? <main className="center"><div className="panel">
        <h1>No pudimos conectar</h1><p role="alert">{bootError}</p>
        <button onClick={() => void boot()}>Volver a intentar</button></div></main> :
      page === '/estado' ? <Status onBack={() => navigate(session ? '/inicio' : '/ingresar')}/> :
      !session ? <Login onSubmit={signIn} busy={busy} error={error} notice={notice}/> :
      <div className="workspace">
        <nav className="sidebar" aria-label="Navegación principal">
          <p className="eyebrow">Mi espacio</p>{link('/inicio', 'Inicio')}{link('/ordenes',session.usuario.perfil==='CLIENTE'?'Mis órdenes':'Órdenes')}{link('/vehiculos',session.usuario.perfil==='CLIENTE'?'Mis vehículos':'Vehículos')}{session.usuario.perfil==='ADMINISTRACION'&&link('/resultados','Resultados y tarifa')}{link('/cuenta', 'Mi cuenta')}
          <div className="sidebar-profile"><span className="avatar" aria-hidden="true">{session.usuario.nombre.charAt(0)}</span>
            <div><strong>{session.usuario.nombre}</strong><small>{names[session.usuario.perfil]}</small></div></div>
        </nav>
        <main className="content">
          {error && <p className="alert" role="alert">{error}</p>}
          {page==='/resultados'?<Resultados perfil={session.usuario.perfil} onExpired={expired} navigate={navigate}/>:page.startsWith('/vehiculos')||page.startsWith('/ordenes') ? <Operacion page={page} session={session} navigate={navigate} onExpired={expired}/> : page === '/cuenta' ? <>
            <p className="eyebrow">Mi cuenta</p><h1>Tus datos de acceso</h1>
            <p className="lead">Esta es la cuenta con la que ingresaste al taller.</p>
            <section className="panel" aria-label="Datos de la cuenta">
              <dl><dt>Nombre</dt><dd>{session.usuario.nombre}</dd><dt>Correo</dt><dd>{session.usuario.correo}</dd>
                <dt>Perfil</dt><dd>{names[session.usuario.perfil]}</dd><dt>Estado de la cuenta</dt><dd><span className="badge">Activa</span></dd></dl>
            </section></> : <>
            <p className="eyebrow">{names[session.usuario.perfil]}</p><h1>Hola, {session.usuario.nombre}.</h1>
            <p className="lead">Bienvenido a tu espacio de trabajo.</p>
            <div className="home-actions">{session.usuario.perfil==='PERSONAL'&&<button className="solid" onClick={()=>navigate('/ordenes/nueva')}>Abrir orden</button>}
              {link('/ordenes',session.usuario.perfil==='CLIENTE'?'Ver mis órdenes':'Ver órdenes','text-link')}
              {link('/vehiculos',session.usuario.perfil==='CLIENTE'?'Ver mis vehículos':'Ver vehículos','text-link')}</div>
            <div className="cards"><section className="panel">
              <span className="badge">Sesión iniciada</span><h2>Tu cuenta</h2>
              <p>{session.usuario.correo}</p>{link('/cuenta', 'Ver mis datos', 'text-link')}
            </section><section className="panel">
              <p className="eyebrow">Acceso</p><h2>{names[session.usuario.perfil]}</h2>
              <p>Las opciones de tu espacio corresponden al perfil de tu cuenta.</p>
            </section></div></>}
          <section className="session-note" aria-label="Información de sesión">
            <h2>Tu sesión</h2><p>Ingresaste a las {hour(session.iniciadaEn)}. Por seguridad, la sesión termina tras
              20 minutos sin actividad o, como máximo, a las {hour(session.limiteAbsoluto)} (hora de Buenos Aires).</p>
          </section>
        </main>
      </div>}
    <footer><span>Gestión de órdenes y margen directo</span>{link('/estado', 'Estado del servicio')}</footer>
  </div>;
}

function Login({ onSubmit, busy, error, notice }: {
  onSubmit: (correo: string, contrasena: string) => Promise<void>;
  busy: boolean; error: string; notice: string;
}) {
  const [correo, setCorreo] = useState('');
  const [contrasena, setContrasena] = useState('');
  const [visible, setVisible] = useState(false);
  async function submit(event: FormEvent) {
    event.preventDefault();
    await onSubmit(correo, contrasena);
    setContrasena('');
  }
  return <main className="login-layout">
    <section className="welcome"><p className="eyebrow">Gestión del taller</p>
      <h1>Cada servicio,<br/>en un mismo lugar.</h1>
      <p>Órdenes, presupuestos y seguimiento para acompañar cada reparación.</p>
      <div className="welcome-rule"/><small>Un espacio para el taller y sus clientes.</small>
    </section>
    <section className="login-panel panel" aria-labelledby="login-title">
      <p className="eyebrow">Tu espacio</p><h2 id="login-title">Ingresá a tu cuenta</h2>
      <p className="muted">Usá el correo y la contraseña de tu cuenta.</p>
      {notice && <p className="notice" role="status">{notice}</p>}
      {error && <p className="alert" role="alert">{error}</p>}
      <form onSubmit={event => void submit(event)}>
        <label htmlFor="correo">Correo electrónico</label>
        <input id="correo" name="correo" type="email" autoComplete="username" autoCapitalize="none"
          spellCheck={false} maxLength={255} value={correo} onChange={e => setCorreo(e.target.value)} required disabled={busy}/>
        <label htmlFor="contrasena">Contraseña</label>
        <div className="password-field"><input id="contrasena" name="contrasena" type={visible ? 'text' : 'password'}
          autoComplete="current-password" maxLength={256} value={contrasena} onChange={e => setContrasena(e.target.value)}
          required disabled={busy}/><button type="button" className="reveal" aria-pressed={visible}
            aria-label={visible ? 'Ocultar contraseña' : 'Mostrar contraseña'} onClick={() => setVisible(!visible)}>
              {visible ? 'Ocultar' : 'Mostrar'}</button></div>
        <button type="submit" className="primary" disabled={busy}>{busy ? 'Verificando…' : 'Ingresar'}</button>
      </form>
      <p className="login-help">Si necesitás habilitar tu cuenta o reponer la contraseña, contactá al responsable del taller.</p>
    </section>
  </main>;
}

function Status({ onBack }: { onBack: () => void }) {
  const [state, setState] = useState<'checking'|'ready'|'unavailable'>('checking');
  const check = useCallback(async () => {
    setState('checking');
    try {
      const response = await fetch('/api/health', { signal: AbortSignal.timeout(5000), cache: 'no-store' });
      const result = await response.json();
      setState(response.ok && result.status === 'UP' ? 'ready' : 'unavailable');
    } catch { setState('unavailable'); }
  }, []);
  useEffect(() => { void check(); }, [check]);
  return <main className="center"><section className="panel status-panel">
    <p className="eyebrow">Disponibilidad</p><h1>Estado del servicio</h1>
    <p role="status" aria-live="polite"><span className={'dot ' + state}/>{state === 'checking' ? 'Comprobando disponibilidad…' :
      state === 'ready' ? 'Servicio disponible' : 'No se pudo conectar. Volvé a intentarlo.'}</p>
    <div className="actions"><button onClick={() => void check()} disabled={state === 'checking'}>Volver a comprobar</button>
      <button className="quiet" onClick={onBack}>Volver</button></div>
  </section></main>;
}
createRoot(document.getElementById('root')!).render(<StrictMode><App/></StrictMode>);
