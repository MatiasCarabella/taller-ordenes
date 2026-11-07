import {useState,type FormEvent} from 'react';
import * as api from './api';
import type {Perfil} from './api';
import {useResource,ErrorBox} from './recursos';
import {ars,fecha,percent} from './formato';
import './ejecucion.css';
interface Fila {ordenId:number;dominio:string;clienteNombre:string;estado:string;finalizadoEn:string;ingresoFinal:string;costoRepuestosTotal:string;
  horasManoObraTotal:string;tarifaHoraAplicada:string;costoManoObra:string;margenDirecto:string;margenPorcentual:string|null}
interface Reporte {desde:string;hasta:string;inicioUtc:string;finExclusivoUtc:string;items:Fila[];page:number;size:number;
  totales:{finalizadas:number;rechazadas:number;canceladas:number;ingreso:string;costoRepuestos:string;costoManoObra:string;margenDirecto:string;margenPorcentual:string|null}}
function today(){return new Intl.DateTimeFormat('sv-SE',{timeZone:'America/Argentina/Buenos_Aires',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());}
export function Resultados(props:{perfil:Perfil;onExpired:()=>void;navigate:(path:string)=>void}){
  if(props.perfil!=='ADMINISTRACION')return <ErrorBox message="Esta sección corresponde a Administración."/>;
  return <Administracion {...props}/>;
}
function Administracion({onExpired,navigate}:{onExpired:()=>void;navigate:(path:string)=>void}){
  const[desde,setDesde]=useState(()=>today().slice(0,8)+'01'),[hasta,setHasta]=useState(today);
  const[query,setQuery]=useState(()=>({desde:today().slice(0,8)+'01',hasta:today(),page:0}));
  const resource=useResource<Reporte>('/api/administracion/resultados?'+new URLSearchParams({desde:query.desde,hasta:query.hasta,page:String(query.page),size:'20'}),onExpired);
  const tariff=useResource<{tarifaHora:string|null}>('/api/administracion/tarifa',onExpired);
  const[value,setValue]=useState<string|null>(null),[saved,setSaved]=useState(''),[error,setError]=useState(''),[busy,setBusy]=useState(false);
  async function save(e:FormEvent){
    e.preventDefault();setError('');setSaved('');setBusy(true);
    try{const r=await api.post<{tarifaHora:string}>('/api/administracion/tarifa',{tarifaHora:(value??tariff.data?.tarifaHora??'').trim().replace(',','.')});setValue(r.tarifaHora);setSaved('Tarifa guardada. Los cierres anteriores conservan su valor.');tariff.reload();}
    catch(e){if(e instanceof api.ApiError&&e.status===401)onExpired();else setError(e instanceof api.ApiError?e.message:'No pudimos guardar la tarifa.');}finally{setBusy(false);}
  }
  const r=resource.data,t=r?.totales;
  return <><p className="eyebrow">Administración</p><h1>Resultados y tarifa</h1><p className="lead">Margen directo de servicios finalizados, según los totales declarados al cierre.</p>
    <section className="panel form-panel"><h2>Tarifa interna vigente</h2>
      {tariff.error?<ErrorBox message={tariff.error} retry={tariff.reload}/>:tariff.loading?<p role="status">Cargando tarifa…</p>:
        <form onSubmit={e=>void save(e)}><label htmlFor="tarifa-hora">Tarifa por hora (ARS)</label><input id="tarifa-hora" required inputMode="decimal" pattern="[0-9]{1,10}([.,][0-9]{1,2})?" disabled={busy}
          value={value??tariff.data?.tarifaHora??''} onChange={e=>{setValue(e.target.value);setSaved('');}}/><p className="field-help">Debe ser positiva. Se copia al confirmar cada cierre.</p>
          <button className="solid" disabled={busy}>Guardar tarifa</button></form>}
      {error&&<ErrorBox message={error}/>} {saved&&<p className="notice" role="status">{saved}</p>}
    </section>
    <section className="panel results-filter"><h2>Período de finalización</h2><form className="actions" onSubmit={e=>{e.preventDefault();setQuery({desde,hasta,page:0});resource.reload();}}>
      <div><label htmlFor="desde">Desde</label><input id="desde" type="date" required value={desde} onChange={e=>setDesde(e.target.value)}/></div>
      <div><label htmlFor="hasta">Hasta (inclusive)</label><input id="hasta" type="date" required value={hasta} min={desde} onChange={e=>setHasta(e.target.value)}/></div>
      <button className="solid">Consultar resultados</button></form>
      <p className="field-help">Fechas de Buenos Aires. Finalizado y Entregado se cuentan una vez por la fecha original de finalización. Rechazadas: fecha de rechazo; canceladas: fecha de cancelación.</p></section>
    {resource.error?<ErrorBox message={resource.error} retry={resource.reload}/>:resource.loading?<p role="status">Consultando resultados…</p>:r&&t&&<>
      <div className="result-cards"><section className="panel"><h2>Ingreso acordado</h2><strong>{ars(t.ingreso)}</strong><p>{t.finalizadas} {t.finalizadas===1?'servicio finalizado':'servicios finalizados'}</p></section>
        <section className="panel"><h2>Margen directo</h2><strong>{ars(t.margenDirecto)}</strong><p>{percent(t.margenPorcentual)}</p></section>
        <section className="panel"><h2>Costos directos</h2><p>Repuestos: {ars(t.costoRepuestos)}</p><p>Mano de obra: {ars(t.costoManoObra)}</p></section></div>
      <p className="notice">Excluidas de las sumas: {t.rechazadas} rechazadas y {t.canceladas} canceladas. El margen excluye gastos generales y cobros; no representa utilidad neta. El porcentaje global se calcula sobre los totales del período.</p>
      <section className="panel results-list"><h2>Detalle por orden</h2>
        {r.items.length===0?<p>No hay servicios finalizados en este período.</p>:r.items.map(f=><details className="result-row" key={f.ordenId}><summary>
          <strong>Orden #{f.ordenId} · {f.dominio}</strong><span>{f.clienteNombre}</span><strong>{ars(f.margenDirecto)} · {percent(f.margenPorcentual)}</strong></summary>
          <p>{f.estado==='ENTREGADO'?'Entregado':'Finalizado'} · Finalización: {fecha(f.finalizadoEn)}</p><dl className="record-data">
            <dt>Ingreso final</dt><dd>{ars(f.ingresoFinal)}</dd><dt>Costo de repuestos</dt><dd>{ars(f.costoRepuestosTotal)}</dd>
            <dt>Horas</dt><dd>{f.horasManoObraTotal.replace('.',',')}</dd><dt>Tarifa aplicada</dt><dd>{ars(f.tarifaHoraAplicada)}</dd>
            <dt>Costo de mano de obra</dt><dd>{ars(f.costoManoObra)}</dd><dt>Margen directo</dt><dd>{ars(f.margenDirecto)}</dd><dt>Margen porcentual</dt><dd>{percent(f.margenPorcentual)}</dd></dl>
          <button onClick={()=>navigate('/ordenes/'+f.ordenId)}>Ver orden #{f.ordenId}</button></details>)}
        <div className="pager"><span>Página {r.page+1} de {Math.max(1,Math.ceil(t.finalizadas/r.size))}</span><div>
          <button disabled={r.page===0} onClick={()=>setQuery({...query,page:r.page-1})}>Anterior</button>
          <button disabled={(r.page+1)*r.size>=t.finalizadas} onClick={()=>setQuery({...query,page:r.page+1})}>Siguiente</button></div></div>
      </section></>}
  </>;
}
