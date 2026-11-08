import { useState, type FormEvent } from 'react';
import * as api from './api';
import { useResource, ErrorBox } from './recursos';
import type { Perfil } from './api';
import {DescargarPdf} from './descarga';

interface Linea {numero:number;tipo:string;descripcion:string;cantidad:string;precioUnitario:string;subtotal:string}
interface Presupuesto {id:number;version:number;autorNombre:string;publicadoEn:string;venceEn:string;total:string;decision:string;decisorNombre:string|null;decididoEn:string|null;
  ultimaVersion:boolean;autorizado:boolean;respuestaVencida:boolean;puedeResponder:boolean;renglones:Linea[]}
interface Contexto {ordenId:number;estado:string;diagnostico:string|null;diagnosticoAutor:string|null;diagnosticadoEn:string|null;ultimaVersion:number;
  presupuestoAutorizadoId:number|null;puedeEditarDiagnostico:boolean;puedePublicar:boolean;ahoraEn:string;presupuestos:Presupuesto[]}
interface Props {ordenId:number;perfil:Perfil;onChanged:()=>void;onExpired:()=>void}
interface DraftLine {tipo:string;descripcion:string;cantidad:string;precioUnitario:string}
interface Payload {ultimaVersionEsperada:number;presupuestoAutorizadoEsperadoId:number|null;venceEn:string;renglones:DraftLine[]}
interface Preview {venceEn:string;total:string;renglones:Linea[]}
const decisionNames:Record<string,string>={PENDIENTE:'Pendiente de respuesta',ACEPTADO:'Aceptado',RECHAZADO:'Rechazado',REVISION:'Revisión solicitada'};
const fecha=(s:string)=>new Intl.DateTimeFormat('es-AR',{dateStyle:'short',timeStyle:'short',timeZone:'America/Argentina/Buenos_Aires',hourCycle:'h23'}).format(new Date(s));
const ars=(s:string)=>new Intl.NumberFormat('es-AR',{style:'currency',currency:'ARS',currencyDisplay:'code',minimumFractionDigits:2,maximumFractionDigits:2}).format(Number(s));
const message=(e:unknown)=>e instanceof api.ApiError?e.message:'No pudimos conectar. Volvé a intentarlo.';
function localInput(s:string) {
  const parts=new Intl.DateTimeFormat('sv-SE',{timeZone:'America/Argentina/Buenos_Aires',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).formatToParts(new Date(s));
  const p=Object.fromEntries(parts.map(v=>[v.type,v.value]));return p.year+'-'+p.month+'-'+p.day+'T'+p.hour+':'+p.minute;
}
function utcInput(value:string) {
  const nominal=Date.parse(value+'Z');if(!Number.isFinite(nominal))throw new Error('Revisá el vencimiento.');
  let instant=nominal;
  for(let i=0;i<2;i++){
    const parts=new Intl.DateTimeFormat('sv-SE',{timeZone:'America/Argentina/Buenos_Aires',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).formatToParts(new Date(instant));
    const p=Object.fromEntries(parts.map(v=>[v.type,v.value]));
    instant+=nominal-Date.UTC(Number(p.year),Number(p.month)-1,Number(p.day),Number(p.hour),Number(p.minute),Number(p.second));
  }
  return new Date(instant).toISOString();
}
function Lines({rows}:{rows:Linea[]}) {
  return <div className="table-wrap budget-table"><table><thead><tr><th>Tipo</th><th>Descripción</th><th>Cantidad</th><th>Precio de venta</th><th>Subtotal</th></tr></thead>
    <tbody>{rows.map(r=><tr key={r.numero}><td>{r.tipo==='REPUESTO'?'Repuesto':'Mano de obra'}</td><td>{r.descripcion}</td>
      <td>{r.cantidad.replace('.',',')}</td><td className="amount-cell">{ars(r.precioUnitario)}</td><td className="amount-cell">{ars(r.subtotal)}</td></tr>)}</tbody></table></div>;
}

export function Presupuestos(props:Props) {
  const resource=useResource<Contexto>('/api/ordenes/'+props.ordenId+'/presupuestos',props.onExpired);
  const[editor,setEditor]=useState(false),[diagnostico,setDiagnostico]=useState<string|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState('');
  const[pending,setPending]=useState<{budget:Presupuesto;decision:string}|null>(null);
  const c=resource.data;
  function failed(e:unknown){if(e instanceof api.ApiError&&e.status===401)props.onExpired();else setError(message(e));}
  async function saveDiagnosis(e:FormEvent) {
    e.preventDefault();if(!c)return;setBusy(true);setError('');
    try{await api.post('/api/ordenes/'+props.ordenId+'/diagnostico',{diagnostico:diagnostico??c.diagnostico??''});props.onChanged();}
    catch(e){failed(e);}finally{setBusy(false);}
  }
  async function decide() {
    if(!pending)return;setBusy(true);setError('');
    try{await api.post('/api/ordenes/'+props.ordenId+'/presupuestos/'+pending.budget.id+'/decision',{decision:pending.decision});props.onChanged();}
    catch(e){failed(e);setPending(null);}finally{setBusy(false);}
  }
  if(resource.error)return <ErrorBox message={resource.error} retry={props.onChanged}/>;
  if(resource.loading||!c)return <p role="status">Cargando diagnóstico y presupuestos…</p>;
  const authorized=c.presupuestos.find(p=>p.autorizado);
  return <section className="budget-area" aria-label="Diagnóstico y presupuestos">
    <section className="panel form-panel"><div className="section-heading"><h2>Diagnóstico</h2><button className="quiet" onClick={props.onChanged}>Actualizar orden</button></div>
      {c.puedeEditarDiagnostico?<form onSubmit={e=>void saveDiagnosis(e)}>
        <label htmlFor="diagnostico">Diagnóstico del vehículo</label><textarea id="diagnostico" rows={4} maxLength={4000} required value={diagnostico??c.diagnostico??''}
          disabled={busy} onChange={e=>setDiagnostico(e.target.value)}/><div className="actions"><button className="solid" disabled={busy} type="submit">{busy?'Guardando…':'Guardar diagnóstico'}</button></div></form>:
        <p className="multiline">{c.diagnostico??'El taller está registrando el diagnóstico del vehículo.'}</p>}
      {c.diagnosticadoEn&&<p className="field-help">Confirmado por {c.diagnosticoAutor} · {fecha(c.diagnosticadoEn)}</p>}
    </section>
    {error&&<div className="alert" role="alert"><p>{error}</p><button onClick={props.onChanged}>Actualizar orden</button></div>}
    <div className="page-title"><div><p className="eyebrow">Propuestas de servicio</p><h2>Presupuestos</h2></div>
      {c.puedePublicar&&<button className="solid" disabled={busy} onClick={()=>setEditor(!editor)}>{editor?'Cerrar borrador':c.ultimaVersion?'Preparar nueva versión':'Preparar presupuesto'}</button>}</div>
    <div className="authorization-note"><strong>Alcance autorizado: {authorized?'versión '+authorized.version:'sin aceptación registrada'}</strong>
      <p>{authorized?ars(authorized.total)+'. El trabajo autorizado corresponde a esta versión aceptada.':'La publicación de una propuesta no autoriza su ejecución.'}</p>
      {c.ultimaVersion>0&&<p>Última propuesta publicada: versión {c.ultimaVersion}.</p>}
    </div>
    {editor&&c.puedePublicar&&<Editor context={c} onChanged={props.onChanged} onExpired={props.onExpired}/>}
    {c.presupuestos.length===0?<div className="empty-state"><h3>Aún no hay propuestas publicadas</h3>
      <p>{props.perfil==='PERSONAL'?(c.puedePublicar?'Prepará la propuesta completa para que el cliente pueda responder.':'Guardá el diagnóstico para preparar el presupuesto completo del servicio.'):'El taller compartirá el presupuesto en esta sección.'}</p></div>:
      <div className="budget-history">{c.presupuestos.map(p=><details className="budget-card" key={p.id} open={p.ultimaVersion}>
        <summary><strong>Versión {p.version}</strong><span className="budget-tags">
          {p.ultimaVersion&&<span className="badge neutral">Más reciente</span>}{p.autorizado&&<span className="badge">Alcance autorizado</span>}
          <span className="badge neutral">{decisionNames[p.decision]}</span></span><strong className="budget-total">{ars(p.total)}</strong></summary>
        <div className="budget-body"><p className="field-help">Publicada por {p.autorNombre} · {fecha(p.publicadoEn)}</p>
          <Lines rows={p.renglones}/><p className="budget-sum">Total de venta <strong>{ars(p.total)}</strong></p>
          <DescargarPdf url={'/api/ordenes/'+props.ordenId+'/presupuestos/'+p.id+'/pdf'} nombre={'presupuesto-orden-'+props.ordenId+'-v'+p.version+'.pdf'} label={'Descargar presupuesto v'+p.version+' (PDF)'} onExpired={props.onExpired}/>
          {p.decision==='PENDIENTE'&&<p className="field-help">Plazo para responder: {fecha(p.venceEn)} (Buenos Aires).</p>}
          {p.respuestaVencida&&<p className="notice">Terminó el plazo de respuesta. Esta propuesta no agrega trabajo autorizado.</p>}
          {!p.ultimaVersion&&p.decision==='PENDIENTE'&&<p className="field-help">Hay una propuesta posterior. Esta versión se conserva para consulta.</p>}
          {p.decididoEn&&<p className="field-help">{decisionNames[p.decision]} por {p.decisorNombre} · {fecha(p.decididoEn)}.</p>}
          {p.puedeResponder&&!pending&&<div className="actions">
            <button className="solid" disabled={busy} onClick={()=>setPending({budget:p,decision:'ACEPTADO'})}>Aceptar versión {p.version}</button>
            <button disabled={busy} onClick={()=>setPending({budget:p,decision:'REVISION'})}>Solicitar revisión de versión {p.version}</button>
            <button disabled={busy} onClick={()=>setPending({budget:p,decision:'RECHAZADO'})}>Rechazar versión {p.version}</button>
          </div>}
          {pending?.budget.id===p.id&&<div className="decision-confirm" role="alertdialog" aria-labelledby={'decision-'+p.id}>
            <h3 id={'decision-'+p.id}>{pending.decision==='ACEPTADO'?'Confirmar aceptación':pending.decision==='RECHAZADO'?'Confirmar rechazo':'Confirmar solicitud de revisión'}</h3>
            <p>Versión {p.version} · {ars(p.total)}. La respuesta se registra una sola vez para esta versión.</p>
            <div className="actions"><button className="solid" disabled={busy} onClick={()=>void decide()}>
              {busy?'Registrando…':pending.decision==='ACEPTADO'?'Confirmar aceptación':pending.decision==='RECHAZADO'?'Confirmar rechazo':'Confirmar solicitud de revisión'}</button>
              <button disabled={busy} onClick={()=>setPending(null)}>Volver</button></div></div>}
        </div></details>)}</div>}
  </section>;
}

function Editor({context:c,onChanged,onExpired}:{context:Contexto;onChanged:()=>void;onExpired:()=>void}) {
  const[rows,setRows]=useState<DraftLine[]>(()=>c.presupuestos[0]?.renglones.map(r=>({tipo:r.tipo,descripcion:r.descripcion,cantidad:r.cantidad,precioUnitario:r.precioUnitario}))??
    [{tipo:'REPUESTO',descripcion:'',cantidad:'1',precioUnitario:'0'}]);
  const[deadline,setDeadline]=useState(()=>localInput(new Date(Date.parse(c.ahoraEn)+24*60*60*1000).toISOString()));
  const[preview,setPreview]=useState<{value:Preview;payload:Payload}|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState('');
  function update(index:number,name:keyof DraftLine,value:string){setRows(old=>old.map((r,i)=>i===index?{...r,[name]:value}:r));setPreview(null);}
  function fail(e:unknown){if(e instanceof api.ApiError&&e.status===401)onExpired();else setError(message(e));}
  async function calculate(e:FormEvent) {
    e.preventDefault();setBusy(true);setError('');
    try{
      const normalized=rows.map(r=>({...r,cantidad:r.cantidad.trim().replace(',','.'),precioUnitario:r.precioUnitario.trim().replace(',','.')}));
      if(normalized.some(r=>!(Number(r.cantidad)>0)))throw new Error('La cantidad de cada renglón debe ser positiva.');
      const payload:Payload={ultimaVersionEsperada:c.ultimaVersion,presupuestoAutorizadoEsperadoId:c.presupuestoAutorizadoId??null,venceEn:utcInput(deadline),renglones:normalized};
      const value=await api.post<Preview>('/api/ordenes/'+c.ordenId+'/presupuestos/previsualizacion',payload);setPreview({value,payload});
    }catch(e){if(e instanceof api.ApiError)fail(e);else setError(e instanceof Error?e.message:message(e));}finally{setBusy(false);}
  }
  async function publish(){
    if(!preview)return;setBusy(true);setError('');
    try{await api.post('/api/ordenes/'+c.ordenId+'/presupuestos',preview.payload);onChanged();}
    catch(e){fail(e);}finally{setBusy(false);}
  }
  return <section className="panel budget-editor" aria-labelledby="editor-title"><h3 id="editor-title">Preparar versión {c.ultimaVersion+1}</h3>
    <p className="field-help">Incluí todo el alcance del servicio, también cuando se trate de una revisión o un adicional. Lo publicado queda fijo.</p>
    {error&&<div className="alert" role="alert"><p>{error}</p><button onClick={onChanged}>Descartar borrador y actualizar</button></div>}
    <form onSubmit={e=>void calculate(e)}>
      <label htmlFor="vence">Responder hasta (hora de Buenos Aires)</label><input id="vence" type="datetime-local" required value={deadline} disabled={busy}
        onChange={e=>{setDeadline(e.target.value);setPreview(null);}}/>
      <p className="field-help">Para cantidades y precios, usá coma o punto decimal sin separador de miles. Ejemplo: 12500,50.</p>
      {rows.map((r,i)=><fieldset className="draft-line" key={i}><legend>Renglón {i+1}</legend>
        <div className="line-main"><div><label htmlFor={'tipo-'+i}>Tipo</label><select id={'tipo-'+i} aria-label={'Tipo del renglón '+(i+1)} value={r.tipo} disabled={busy} onChange={e=>update(i,'tipo',e.target.value)}>
          <option value="REPUESTO">Repuesto</option><option value="MANO_OBRA">Mano de obra</option></select></div>
        <div><label htmlFor={'descripcion-'+i}>Descripción</label><input id={'descripcion-'+i} aria-label={'Descripción del renglón '+(i+1)} required maxLength={255} value={r.descripcion} disabled={busy} onChange={e=>update(i,'descripcion',e.target.value)}/></div></div>
        <div className="line-values"><div><label htmlFor={'cantidad-'+i}>Cantidad</label><input id={'cantidad-'+i} aria-label={'Cantidad del renglón '+(i+1)} inputMode="decimal" required pattern="[0-9]{1,6}([.,][0-9]{1,2})?" value={r.cantidad} disabled={busy} onChange={e=>update(i,'cantidad',e.target.value)}/></div>
          <div><label htmlFor={'precio-'+i}>Precio de venta unitario (ARS)</label><input id={'precio-'+i} aria-label={'Precio del renglón '+(i+1)} inputMode="decimal" required pattern="[0-9]{1,10}([.,][0-9]{1,2})?" value={r.precioUnitario} disabled={busy} onChange={e=>update(i,'precioUnitario',e.target.value)}/></div>
          <button type="button" aria-label={'Quitar renglón '+(i+1)} disabled={busy||rows.length===1} onClick={()=>{setRows(rows.filter((_,n)=>n!==i));setPreview(null);}}>Quitar</button></div>
      </fieldset>)}
      <div className="actions"><button type="button" disabled={busy||rows.length>=100} onClick={()=>{setRows([...rows,{tipo:'REPUESTO',descripcion:'',cantidad:'1',precioUnitario:'0'}]);setPreview(null);}}>Agregar renglón</button>
        <button type="submit" className="solid" disabled={busy}>{busy?'Procesando…':'Revisar presupuesto'}</button></div>
    </form>
    {preview&&<section className="budget-preview" aria-label="Vista previa del presupuesto"><h3>Revisá antes de publicar</h3><Lines rows={preview.value.renglones}/>
      <p className="budget-sum">Total de venta <strong>{ars(preview.value.total)}</strong></p><p className="field-help">Respuesta hasta {fecha(preview.value.venceEn)}. Una vez publicada, esta versión no se puede editar.</p>
      <button className="solid" disabled={busy} onClick={()=>void publish()}>{busy?'Publicando…':'Confirmar publicación'}</button></section>}
  </section>;
}
