import {useState,type FormEvent} from 'react';
import * as api from './api';
import {useResource,ErrorBox} from './recursos';
import {fecha} from './formato';
import './canales.css';

interface Estado {correo:string;correoConfirmado:boolean;telegramVinculado:boolean;tokenPendiente:string|null;tokenVenceEn:string|null;smtpConfigurado:boolean;telegramConfigurado:boolean;conexionTelegram:string}
const connection:Record<string,string>={CONECTADO:'Conexión disponible',SIN_CONFIGURAR:'El taller todavía no habilitó Telegram',NO_DISPONIBLE:'No se pudo conectar con Telegram',WEBHOOK_ACTIVO:'El bot requiere revisar su configuración',USUARIO_INCORRECTO:'El bot requiere revisar su configuración'};
export function Canales({onExpired,onAccountChanged}:{onExpired:()=>void;onAccountChanged:()=>Promise<void>}){
  const resource=useResource<Estado>('/api/canales',onExpired);
  const[email,setEmail]=useState<string|null>(null),[code,setCode]=useState(''),[link,setLink]=useState<{enlace:string;venceEn:string}|null>(null);
  const[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState(''),[unlink,setUnlink]=useState(false);
  function fail(e:unknown){if(e instanceof api.ApiError&&e.status===401)onExpired();else setError(e instanceof api.ApiError?e.message:'No pudimos completar la solicitud. Volvé a intentarlo.');}
  async function action(path:string,body:unknown,success:string){
    setBusy(true);setError('');setNotice('');
    try{await api.post('/api/canales'+path,body);setLink(null);setCode('');setEmail(null);setUnlink(false);resource.reload();setNotice(success);if(path==='/correo')await onAccountChanged();}
    catch(e){fail(e);}finally{setBusy(false);}
  }
  async function requestCode(){
    setBusy(true);setError('');setNotice('');
    try{const r=await api.post<{envio:api.ResultadoCanal}>('/api/canales/correo/codigo');setLink(null);setCode('');resource.reload();setNotice(r.envio.mensaje);}
    catch(e){fail(e);}finally{setBusy(false);}
  }
  async function requestLink(){
    setBusy(true);setError('');setNotice('');
    try{const r=await api.post<{enlace:string;venceEn:string}>('/api/canales/telegram/enlace');setLink(r);setCode('');resource.reload();setNotice('Abrí el enlace e iniciá el bot. Después volvé y actualizá el estado.');}
    catch(e){fail(e);}finally{setBusy(false);}
  }
  function update(){setError('');resource.reload();}
  if(resource.error)return <ErrorBox message={resource.error} retry={resource.reload}/>;
  if(resource.loading||!resource.data)return <p role="status">Cargando canales…</p>;
  const c=resource.data;
  const displayedNotice=notice.startsWith('Abrí el enlace')&&c.telegramVinculado&&c.tokenPendiente!=='TELEGRAM'?'Telegram vinculado. Ya podés recibir avisos por este canal.':notice;
  return <section className="channels" aria-label="Canales de contacto"><div className="section-heading"><h2>Canales de contacto</h2><button onClick={update} disabled={busy}>Actualizar estado</button></div>
    <p>Recibí avisos de tus órdenes. Los presupuestos se responden desde el portal, aunque un aviso no llegue.</p>
    {error&&<ErrorBox message={error}/>} {displayedNotice&&<p className="notice" role="status">{displayedNotice}</p>}
    <p className="field-help">Los códigos y enlaces vencen a los diez minutos y se usan una vez. Generar uno nuevo invalida el anterior, incluso si era del otro canal.</p>
    {c.tokenVenceEn&&<p className="field-help">Solicitud pendiente de {c.tokenPendiente==='CORREO'?'correo':'Telegram'} hasta {fecha(c.tokenVenceEn)}.</p>}
    <section className="panel form-panel"><h3>Correo</h3><p><span className="badge neutral">{c.correoConfirmado?'Confirmado':'Sin confirmar'}</span></p>
      <form onSubmit={(e:FormEvent)=>{e.preventDefault();void action('/correo',{correo:email??c.correo},'Datos de correo actualizados. Usá esta dirección para el próximo ingreso.');}}>
        <label htmlFor="canal-correo">Correo de tu cuenta</label><input id="canal-correo" type="email" autoComplete="email" required maxLength={255} disabled={busy} value={email??c.correo} onChange={e=>setEmail(e.target.value)}/>
        <p className="field-help">También se usa para ingresar. Cambiarlo quita su confirmación y cancela el código o enlace pendiente; no cambia tu contraseña.</p>
        <button disabled={busy}>Guardar correo</button>
      </form>
      {!c.correoConfirmado&&<>
        {!c.smtpConfigurado&&<p className="notice">El taller todavía no habilitó el envío de correo.</p>}
        <div className="channel-actions"><button className="solid" disabled={busy||!c.smtpConfigurado} onClick={()=>void requestCode()}>Solicitar código de correo</button></div>
        <form onSubmit={e=>{e.preventDefault();void action('/correo/confirmacion',{codigo:code.trim()},'Correo confirmado. Ya podés recibir avisos por este canal.');}}>
          <label htmlFor="codigo-correo">Código recibido por correo</label><input id="codigo-correo" autoComplete="one-time-code" spellCheck={false} autoCapitalize="none" required minLength={22} maxLength={22} pattern="(?:[A-Za-z0-9_]|-){22}" value={code} disabled={busy} onChange={e=>setCode(e.target.value)}/>
          <p className="field-help">Pegá el código de 22 caracteres del mensaje, respetando mayúsculas y minúsculas.</p><button className="solid" disabled={busy}>Confirmar correo</button>
        </form></>}
    </section>
    <section className="panel form-panel"><h3>Telegram</h3><p><span className="badge neutral">{c.telegramVinculado?'Vinculado':'Sin vincular'}</span></p>
      <p className="field-help">{c.telegramConfigurado?(connection[c.conexionTelegram]??'Comprobando conexión con el bot'):connection.SIN_CONFIGURAR}.</p>
      <button className="solid" disabled={busy||!c.telegramConfigurado||c.conexionTelegram!=='CONECTADO'} onClick={()=>void requestLink()}>{c.telegramVinculado?'Generar enlace para otro chat':'Generar enlace de Telegram'}</button>
      {link&&c.tokenPendiente==='TELEGRAM'&&<div className="telegram-link"><a href={link.enlace} target="_blank" rel="noopener noreferrer">Abrir Telegram y vincular</a>
        <p className="field-help">Válido hasta {fecha(link.venceEn)}. Abrilo en un chat privado e iniciá el bot con la cuenta que querés vincular.</p></div>}
      {c.telegramVinculado&&<div className="channel-actions"><button disabled={busy} onClick={()=>setUnlink(!unlink)}>Desvincular Telegram</button>
        {unlink&&<div className="decision-confirm" role="alertdialog" aria-label="Desvincular Telegram"><p>Dejarás de recibir avisos en ese chat. También se cancelará cualquier código o enlace pendiente.</p>
          <button disabled={busy} onClick={()=>void action('/telegram/desvincular',{},'Telegram desvinculado.')}>Confirmar desvinculación</button>
          <button disabled={busy} onClick={()=>setUnlink(false)}>Volver</button></div>}</div>}
    </section>
  </section>;
}
