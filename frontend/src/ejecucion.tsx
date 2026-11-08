import { useState, type FormEvent } from 'react';
import * as api from './api';
import type { Perfil } from './api';
import {useResource,ErrorBox} from './recursos';
import {ars,fecha} from './formato';
import {DescargarPdf} from './descarga';
import './ejecucion.css';

interface Contexto {ordenId:number;estado:string;presupuestoAutorizadoId:number|null;versionAutorizada:number|null;trabajoRealizado:string|null;
  iniciadoEn:string|null;finalizadoEn:string|null;entregadoEn:string|null;canceladoEn:string|null;motivoCancelacion:string|null;ingresoFinal:string|null;
  puedeIniciar:boolean;puedeGuardarTrabajo:boolean;puedeCerrar:boolean;puedeEntregar:boolean;puedeCancelar:boolean}
interface Datos {trabajoRealizado:string;presupuestoAutorizadoId:number;alcanceConfirmado:boolean;costoRepuestosTotal:string;horasManoObraTotal:string}
interface Vista {ordenId:number;versionAutorizada:number;trabajoRealizado:string;ingresoFinal:string;costoRepuestosTotal:string;
  horasManoObraTotal:string;tarifaHoraAplicada:string;costoManoObra:string;confirmacion:string;venceEn:string}
const message=(e:unknown)=>e instanceof api.ApiError?e.message:'No pudimos conectar. Volvé a intentarlo.';
export function Ejecucion({ordenId,perfil,onChanged,onExpired}:{ordenId:number;perfil:Perfil;onChanged:()=>void;onExpired:()=>void}){
  const resource=useResource<Contexto>('/api/ordenes/'+ordenId+'/ejecucion',onExpired);
  const[work,setWork]=useState<string|null>(null),[confirmed,setConfirmed]=useState(false),[parts,setParts]=useState(''),[hours,setHours]=useState('');
  const[closing,setClosing]=useState(false),[cancel,setCancel]=useState(false),[reason,setReason]=useState(''),[confirmAction,setConfirmAction]=useState<'inicio'|'entrega'|null>(null);
  const[preview,setPreview]=useState<{vista:Vista;datos:Datos}|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState('');
  function fail(e:unknown){setPreview(null);if(e instanceof api.ApiError&&e.status===401)onExpired();else setError(message(e));}
  async function action(path:string,payload:unknown){
    setBusy(true);setError('');try{await api.post('/api/ordenes/'+ordenId+path,payload);onChanged();}catch(e){fail(e);}finally{setBusy(false);}
  }
  async function review(e:FormEvent){
    e.preventDefault();if(!resource.data?.presupuestoAutorizadoId)return;setBusy(true);setError('');setPreview(null);
    const datos:Datos={trabajoRealizado:work??resource.data.trabajoRealizado??'',presupuestoAutorizadoId:resource.data.presupuestoAutorizadoId,
      alcanceConfirmado:confirmed,costoRepuestosTotal:parts.trim().replace(',','.'),horasManoObraTotal:hours.trim().replace(',','.')};
    try{const vista=await api.post<Vista>('/api/ordenes/'+ordenId+'/cierre/previsualizacion',datos);setPreview({vista,datos});}catch(e){fail(e);}finally{setBusy(false);}
  }
  if(resource.error)return <ErrorBox message={resource.error} retry={onChanged}/>;
  if(resource.loading||!resource.data)return <p role="status">Cargando ejecución…</p>;
  const c=resource.data,closed=c.estado==='FINALIZADO'||c.estado==='ENTREGADO';
  return <section className="execution-area" aria-label="Ejecución y cierre"><section className="panel form-panel">
    <div className="section-heading"><h2>Trabajo y entrega</h2><button className="quiet" onClick={onChanged}>Actualizar ejecución</button></div>
    {error&&<ErrorBox message={error}/>}
    {c.iniciadoEn&&<p>Reparación iniciada: {fecha(c.iniciadoEn)}.</p>}
    {c.versionAutorizada&&<p className="notice">Alcance autorizado: versión {c.versionAutorizada}. Solo se ejecuta el trabajo de esa versión.</p>}
    {c.puedeIniciar&&<button className="solid" disabled={busy} onClick={()=>setConfirmAction('inicio')}>Iniciar reparación</button>}
    {c.puedeGuardarTrabajo?<form onSubmit={e=>{e.preventDefault();void action('/trabajo',{trabajoRealizado:work??c.trabajoRealizado??'',presupuestoAutorizadoId:c.presupuestoAutorizadoId,alcanceConfirmado:confirmed});}}>
      <label htmlFor="trabajo">Trabajo realizado</label><textarea id="trabajo" required rows={5} maxLength={4000} disabled={busy} value={work??c.trabajoRealizado??''}
        onChange={e=>{setWork(e.target.value);setPreview(null);}}/>
      <label className="check-row"><input type="checkbox" required checked={confirmed} disabled={busy} onChange={e=>{setConfirmed(e.target.checked);setPreview(null);}}/>
        Confirmo que lo realizado corresponde al alcance autorizado (versión {c.versionAutorizada}).</label>
      <p className="field-help">Una propuesta adicional pendiente, rechazada o vencida no autoriza su ejecución.</p>
      <div className="actions"><button disabled={busy} type="submit">Guardar trabajo</button>
        {c.puedeCerrar&&<button type="button" className="solid" disabled={busy} onClick={()=>{setClosing(!closing);setPreview(null);}}> {closing?'Volver al trabajo':'Preparar cierre'}</button>}</div>
    </form>:<p className="multiline">{c.trabajoRealizado??(c.estado==='RECHAZADO'||c.estado==='CANCELADO'?'El servicio cerró sin reparación.':'El trabajo se registrará durante la reparación.')}</p>}
    {c.finalizadoEn&&<p>Finalizado: {fecha(c.finalizadoEn)}.</p>}
    {c.ingresoFinal!==null&&<p>Importe final acordado: <strong>{ars(c.ingresoFinal)}</strong>.</p>}
    {closed&&<p className="field-help">El trabajo final, la autorización y los valores del cierre quedaron fijos. No se admite edición ni reapertura.</p>}
    {c.entregadoEn&&<p className="notice">Entregado: {fecha(c.entregadoEn)}.</p>}
    {closed&&<DescargarPdf url={'/api/ordenes/'+ordenId+'/resumen/pdf'} nombre={'resumen-orden-'+ordenId+'.pdf'} label="Descargar resumen final (PDF)" onExpired={onExpired}/>}
    {c.puedeEntregar&&<button className="solid" disabled={busy} onClick={()=>setConfirmAction('entrega')}>Registrar entrega</button>}
    {c.canceladoEn&&<><p>Cancelado: {fecha(c.canceladoEn)}.</p><p className="multiline">Motivo: {c.motivoCancelacion}</p></>}
    {confirmAction&&<div className="decision-confirm" role="alertdialog" aria-label="Confirmación de operación"><h3>{confirmAction==='inicio'?'Confirmar inicio de reparación':'Confirmar entrega'}</h3>
      <p>{confirmAction==='inicio'?'Se iniciará el trabajo de la versión autorizada '+c.versionAutorizada+'.':'La entrega conserva la fecha y los valores originales del cierre.'}</p>
      <div className="actions"><button className="solid" disabled={busy} onClick={()=>void action(confirmAction==='inicio'?'/iniciar':'/entrega',confirmAction==='inicio'?{presupuestoAutorizadoId:c.presupuestoAutorizadoId}:{})}>Confirmar {confirmAction==='inicio'?'inicio':'entrega'}</button>
        <button disabled={busy} onClick={()=>setConfirmAction(null)}>Volver</button></div></div>}
    {perfil==='ADMINISTRACION'&&c.puedeCancelar&&<div className="cancel-area"><button disabled={busy} onClick={()=>setCancel(!cancel)}>{cancel?'Volver':'Cancelar orden'}</button>
      {cancel&&<form onSubmit={e=>{e.preventDefault();void action('/cancelacion',{motivo:reason});}}><label htmlFor="motivo-cancelacion">Motivo de cancelación</label>
        <textarea id="motivo-cancelacion" rows={3} required maxLength={4000} value={reason} disabled={busy} onChange={e=>setReason(e.target.value)}/>
        <p>La orden cerrará sin reparación y quedará excluida del margen.</p><button className="solid" disabled={busy}>Confirmar cancelación</button></form>}</div>}
  </section>
  {closing&&c.puedeCerrar&&<section className="panel form-panel close-editor"><h2>Preparar cierre</h2>
    <p>El importe final será el total de la versión autorizada. Cargá los costos efectivamente declarados y las horas totales.</p>
    <form onSubmit={e=>void review(e)}>
      <label htmlFor="costo-repuestos">Costo total de repuestos (ARS)</label><input id="costo-repuestos" inputMode="decimal" required pattern="[0-9]{1,10}([.,][0-9]{1,2})?" disabled={busy} value={parts} onChange={e=>{setParts(e.target.value);setPreview(null);}}/>
      <label htmlFor="horas-trabajo">Horas totales de mano de obra</label><input id="horas-trabajo" inputMode="decimal" required pattern="[0-9]{1,6}([.,][0-9]{1,2})?" disabled={busy} value={hours} onChange={e=>{setHours(e.target.value);setPreview(null);}}/>
      <p className="field-help">Se admiten cero y hasta dos decimales. El trabajo y la declaración de alcance del formulario anterior forman parte del cierre.</p>
      {!confirmed&&<p className="notice">Revisá el trabajo y confirmá su correspondencia con la versión autorizada antes de continuar.</p>}
      <button className="solid" disabled={busy||!confirmed||!(work??c.trabajoRealizado??'').trim()}>Revisar cierre</button>
    </form>
    {preview&&<section className="close-preview" aria-label="Vista previa de cierre"><h3>Revisá antes de finalizar</h3><p>Versión autorizada: {preview.vista.versionAutorizada}.</p>
      <p className="multiline">{preview.vista.trabajoRealizado}</p><dl className="record-data">
        <dt>Ingreso final acordado</dt><dd>{ars(preview.vista.ingresoFinal)}</dd><dt>Costo de repuestos</dt><dd>{ars(preview.vista.costoRepuestosTotal)}</dd>
        <dt>Horas totales</dt><dd>{preview.vista.horasManoObraTotal.replace('.',',')}</dd><dt>Tarifa por hora aplicada</dt><dd>{ars(preview.vista.tarifaHoraAplicada)}</dd>
        <dt>Costo de mano de obra</dt><dd>{ars(preview.vista.costoManoObra)}</dd></dl>
      <p className="notice">Al confirmar, los datos quedarán fijos. No se permite reabrir ni modificar un cierre. Si cambian el trabajo, el alcance o la tarifa, tendrás que revisar otra vista previa.</p>
      <p className="field-help">Vista previa válida hasta {fecha(preview.vista.venceEn)}.</p>
      <button className="solid" disabled={busy} onClick={()=>void action('/cierre',{datos:preview.datos,confirmacion:preview.vista.confirmacion})}>Confirmar finalización</button>
    </section>}
  </section>}</section>;
}
