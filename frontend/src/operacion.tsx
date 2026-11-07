import { useEffect, useState, type FormEvent } from 'react';
import * as api from './api';
import type { Sesion } from './api';
import {useResource,ErrorBox} from './recursos';
import {Presupuestos} from './presupuestos';
import {Ejecucion} from './ejecucion';
import './presupuestos.css';

interface Cuenta { id:number; nombre:string; correo:string }
interface Vehiculo { id:number; clienteId:number; clienteNombre:string; clienteActivo:boolean; dominio:string; marca:string; modelo:string }
interface Orden { id:number; vehiculoId:number; dominio:string; marca:string; modelo:string; clienteId:number; clienteNombre:string;
  responsableId:number; responsableNombre:string; motivoIngreso:string; estado:string; recibidoEn:string; actualizadoEn:string }
interface Pagina<T> { items:T[]; page:number; size:number; total:number }
interface Registro { vehiculo:Vehiculo; creado:boolean }
interface Props { page:string; session:Sesion; navigate:(path:string)=>void; onExpired:()=>void }
const estados:Record<string,string>={RECIBIDO:'Recibido',DIAGNOSTICADO:'Diagnosticado',PENDIENTE_APROBACION:'Pendiente de aprobación',
  APROBADO:'Aprobado',EN_REPARACION:'En reparación',FINALIZADO:'Finalizado',ENTREGADO:'Entregado',RECHAZADO:'Rechazado',CANCELADO:'Cancelado'};
const fecha=(value:string)=>new Intl.DateTimeFormat('es-AR',{dateStyle:'short',timeStyle:'short',timeZone:'America/Argentina/Buenos_Aires',hourCycle:'h23'}).format(new Date(value));
const mensaje=(e:unknown)=>e instanceof api.ApiError?e.message:'No pudimos conectar. Volvé a intentarlo.';
function Pager({page,total,size,move}:{page:number;total:number;size:number;move:(n:number)=>void}) {
  return <div className="pager"><span>Página {page+1} de {Math.max(1,Math.ceil(total/size))}</span><div>
    <button disabled={page===0} onClick={()=>move(page-1)}>Anterior</button>
    <button disabled={(page+1)*size>=total} onClick={()=>move(page+1)}>Siguiente</button></div></div>;
}
function VehicleInfo({vehicle}:{vehicle:Vehiculo}) {
  return <dl className="record-data"><dt>Dominio</dt><dd><strong>{vehicle.dominio}</strong></dd>
    <dt>Vehículo</dt><dd>{vehicle.marca} {vehicle.modelo}</dd><dt>Titular</dt><dd>{vehicle.clienteNombre}
      {!vehicle.clienteActivo&&<span className="inactive">Cliente inactivo</span>}</dd></dl>;
}
function denied(){return <ErrorBox message="Esta acción corresponde al personal del taller."/>;}

export function Operacion(props:Props) {
  const path=props.page.split('?')[0];
  if(path==='/vehiculos')return <VehicleList key={props.page} {...props}/>;
  if(path.startsWith('/vehiculos/'))return <VehicleDetail key={props.page} {...props}/>;
  if(path==='/ordenes/nueva')return props.session.usuario.perfil==='PERSONAL'?<NewOrder key={props.page} {...props}/>:denied();
  if(path==='/ordenes')return <OrderList key={props.page} {...props}/>;
  return <OrderDetail key={props.page} {...props}/>;
}
function VehicleList(props:Props) {
  const params=new URLSearchParams(props.page.split('?')[1]);
  const page=Math.max(0,Number(params.get('page'))||0),query=params.get('q')??'';
  const[q,setQ]=useState(query),[register,setRegister]=useState(false);
  const personal=props.session.usuario.perfil==='PERSONAL';
  const resource=useResource<Pagina<Vehiculo>>('/api/vehiculos?'+new URLSearchParams({q:query,page:String(page),size:'20'}),props.onExpired);
  const move=(p:number)=>props.navigate('/vehiculos?'+new URLSearchParams({q:query,page:String(p)}));
  function search(e:FormEvent){e.preventDefault();props.navigate('/vehiculos?'+new URLSearchParams({q,page:'0'}));}
  return <>
    <div className="page-title"><div><p className="eyebrow">Vehículos</p><h1>{props.session.usuario.perfil==='CLIENTE'?'Mis vehículos':'Vehículos del taller'}</h1></div>
      {personal&&<button className="solid" onClick={()=>setRegister(!register)}>{register?'Cerrar registro':'Registrar vehículo'}</button>}</div>
    {register&&<RegisterVehicle {...props} onSaved={resource.reload}/>}
    <form className="filters" onSubmit={search}><label htmlFor="buscar-vehiculo">Buscar vehículo</label><div className="search-row">
      <input id="buscar-vehiculo" placeholder="Dominio, marca, modelo o titular" value={q} maxLength={100} onChange={e=>setQ(e.target.value)}/>
      <button type="submit">Buscar</button></div></form>
    {resource.error?<ErrorBox message={resource.error} retry={resource.reload}/>:resource.loading?<p role="status">Cargando vehículos…</p>:
      resource.data&&<>
        <p className="result-count">{resource.data.total} {resource.data.total===1?'vehículo':'vehículos'}</p>
        {resource.data.items.length===0?<div className="empty-state"><h2>No encontramos vehículos</h2><p>{query?'Probá con otro dominio, marca o modelo.':
          personal?'Registrá un vehículo para comenzar a asociar sus servicios.':'Los vehículos registrados aparecerán en este listado.'}</p></div>:
          <div className="table-wrap"><table><thead><tr><th>Dominio</th><th>Vehículo</th><th>Titular</th><th><span className="sr-only">Acciones</span></th></tr></thead>
            <tbody>{resource.data.items.map(v=><tr key={v.id}><td><button className="table-link" onClick={()=>props.navigate('/vehiculos/'+v.id)}>{v.dominio}</button></td>
              <td>{v.marca} {v.modelo}</td><td>{v.clienteNombre}{!v.clienteActivo&&<small className="inactive">Inactivo</small>}</td>
              <td>{personal&&<button disabled={!v.clienteActivo} onClick={()=>props.navigate('/ordenes/nueva?vehiculo='+v.id)}>Abrir orden</button>}</td></tr>)}</tbody></table></div>}
        <Pager page={page} total={resource.data.total} size={20} move={move}/>
      </>}
  </>;
}
function RegisterVehicle(props:Props&{onSaved:()=>void}) {
  const clients=useResource<Cuenta[]>('/api/catalogos/clientes',props.onExpired);
  const[cliente,setCliente]=useState(''),[dominio,setDominio]=useState(''),[marca,setMarca]=useState(''),[modelo,setModelo]=useState('');
  const[busy,setBusy]=useState(false),[error,setError]=useState(''),[result,setResult]=useState<Registro|null>(null);
  async function save(e:FormEvent) {
    e.preventDefault();setBusy(true);setError('');
    try {setResult(await api.post<Registro>('/api/vehiculos',{clienteId:Number(cliente),dominio,marca,modelo}));props.onSaved();}
    catch(e){if(e instanceof api.ApiError&&e.status===401)props.onExpired();else setError(mensaje(e));}
    finally{setBusy(false);}
  }
  if(result)return <section className="panel form-panel" aria-label="Resultado de registro">
    <p className="notice" role="status">{result.creado?'Vehículo registrado.':'Este dominio ya tiene una ficha registrada.'}</p>
    <VehicleInfo vehicle={result.vehiculo}/><div className="actions">
      <button className="solid" disabled={!result.vehiculo.clienteActivo} onClick={()=>props.navigate('/ordenes/nueva?vehiculo='+result.vehiculo.id)}>Abrir orden para {result.vehiculo.dominio}</button>
      <button onClick={()=>props.navigate('/vehiculos/'+result.vehiculo.id)}>Ver ficha</button>
      <button className="quiet" onClick={()=>{setResult(null);setDominio('');setMarca('');setModelo('');}}>Registrar otro vehículo</button>
    </div></section>;
  return <section className="panel form-panel" aria-labelledby="nuevo-vehiculo-title">
    <h2 id="nuevo-vehiculo-title">Registrar vehículo</h2>
    {clients.error&&<ErrorBox message={clients.error} retry={clients.reload}/>}
    {error&&<ErrorBox message={error}/>}
    <form onSubmit={e=>void save(e)}>
      <label htmlFor="cliente">Cliente titular</label><select id="cliente" value={cliente} required disabled={busy||clients.loading} onChange={e=>setCliente(e.target.value)}>
        <option value="">Seleccionar cliente</option>{clients.data?.map(c=><option key={c.id} value={c.id}>{c.nombre} · {c.correo}</option>)}</select>
      {clients.data?.length===0&&<p className="field-help">No hay clientes activos. Pedí al responsable del taller que habilite las cuentas.</p>}
      <div className="form-grid"><div><label htmlFor="dominio">Dominio</label><input id="dominio" placeholder="AB123CD" value={dominio} maxLength={30} required disabled={busy} onChange={e=>setDominio(e.target.value)}/></div>
        <div><label htmlFor="marca">Marca</label><input id="marca" value={marca} maxLength={80} required disabled={busy} onChange={e=>setMarca(e.target.value)}/></div>
        <div><label htmlFor="modelo">Modelo</label><input id="modelo" value={modelo} maxLength={80} required disabled={busy} onChange={e=>setModelo(e.target.value)}/></div></div>
      <div className="actions"><button className="solid" type="submit" disabled={busy||!clients.data?.length}>{busy?'Guardando…':'Guardar vehículo'}</button></div>
    </form></section>;
}
function VehicleDetail(props:Props) {
  const id=props.page.split('?')[0].split('/').pop();
  const resource=useResource<Vehiculo>('/api/vehiculos/'+id,props.onExpired);
  return <><button className="back-link" onClick={()=>props.navigate('/vehiculos')}>← Volver a vehículos</button>
    {resource.error?<ErrorBox message={resource.error}/>:resource.loading?<p role="status">Cargando ficha…</p>:resource.data&&<>
      <p className="eyebrow">Ficha del vehículo</p><h1>{resource.data.dominio}</h1>
      <section className="panel"><VehicleInfo vehicle={resource.data}/></section><div className="actions">
        {props.session.usuario.perfil==='PERSONAL'&&<button className="solid" disabled={!resource.data.clienteActivo} onClick={()=>props.navigate('/ordenes/nueva?vehiculo='+id)}>Abrir orden</button>}
        <button onClick={()=>props.navigate('/ordenes?vehiculoId='+id)}>Ver órdenes del vehículo</button>
      </div></>}</>;
}
function NewOrder(props:Props) {
  const initial=new URLSearchParams(props.page.split('?')[1]).get('vehiculo');
  const prefill=useResource<Vehiculo>(initial?'/api/vehiculos/'+encodeURIComponent(initial):null,props.onExpired);
  const staff=useResource<Cuenta[]>('/api/catalogos/personal',props.onExpired);
  const[chosen,setChosen]=useState<Vehiculo|null>(null),[q,setQ]=useState(''),[query,setQuery]=useState(''),[searchPage,setSearchPage]=useState(0);
  const[responsable,setResponsable]=useState(String(props.session.usuario.id)),[motivo,setMotivo]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('');
  const choices=useResource<Pagina<Vehiculo>>(!chosen?'/api/vehiculos?'+new URLSearchParams({q:query,size:'10',page:String(searchPage)}):null,props.onExpired);
  useEffect(()=>{if(prefill.data)setChosen(prefill.data);},[prefill.data]);
  async function save(e:FormEvent) {
    e.preventDefault();if(!chosen)return;setBusy(true);setError('');
    try {const order=await api.post<Orden>('/api/ordenes',{vehiculoId:chosen.id,responsableId:Number(responsable),motivoIngreso:motivo});props.navigate('/ordenes/'+order.id);}
    catch(e){if(e instanceof api.ApiError&&e.status===401)props.onExpired();else setError(mensaje(e));}
    finally{setBusy(false);}
  }
  return <><button className="back-link" onClick={()=>props.navigate('/ordenes')}>← Volver a órdenes</button>
    <p className="eyebrow">Recepción</p><h1>Abrir orden</h1><p className="lead">Seleccioná el vehículo e indicá el motivo del servicio.</p>
    {(prefill.error||staff.error||error)&&<ErrorBox message={error||prefill.error||staff.error}/>}
    <section className="panel form-panel"><h2>Vehículo y titular</h2>
      {chosen?<><VehicleInfo vehicle={chosen}/><button className="quiet" disabled={busy} onClick={()=>setChosen(null)}>Elegir otro vehículo</button></>:
        prefill.loading?<p role="status">Cargando vehículo…</p>:<>
          <form className="filters compact" onSubmit={e=>{e.preventDefault();setQuery(q);setSearchPage(0);}}><label htmlFor="seleccionar-vehiculo">Buscar vehículo registrado</label>
            <div className="search-row"><input id="seleccionar-vehiculo" value={q} maxLength={100} placeholder="Dominio, marca o titular" onChange={e=>setQ(e.target.value)}/><button type="submit">Buscar</button></div></form>
          {choices.error?<ErrorBox message={choices.error} retry={choices.reload}/>:choices.loading?<p role="status">Buscando vehículos…</p>:choices.data&&<>
            <ul className="vehicle-options">{choices.data.items.map(v=><li key={v.id}><div><strong>{v.dominio}</strong><span>{v.marca} {v.modelo} · {v.clienteNombre}</span></div>
              <button aria-label={'Seleccionar '+v.dominio} disabled={!v.clienteActivo} onClick={()=>setChosen(v)}>Seleccionar</button></li>)}</ul>
            {choices.data.items.length===0&&<p>No encontramos vehículos con esa búsqueda.</p>}
            <Pager page={searchPage} total={choices.data.total} size={10} move={setSearchPage}/></>}
          <button className="text-link quiet" onClick={()=>props.navigate('/vehiculos')}>Registrar un vehículo</button>
        </>}
    </section>
    <section className="panel form-panel"><h2>Datos de ingreso</h2><form onSubmit={e=>void save(e)}>
      <label htmlFor="responsable">Personal responsable</label><select id="responsable" value={responsable} required disabled={busy||staff.loading} onChange={e=>setResponsable(e.target.value)}>
        <option value="">Seleccionar responsable</option>{staff.data?.map(p=><option key={p.id} value={p.id}>{p.nombre}</option>)}</select>
      <label htmlFor="motivo">Motivo de ingreso</label><textarea id="motivo" rows={4} maxLength={4000} value={motivo} required disabled={busy} onChange={e=>setMotivo(e.target.value)}/>
      <p className="field-help">La orden se abrirá como Recibido para el titular del vehículo seleccionado.</p>
      <div className="actions"><button className="solid" type="submit" disabled={busy||!chosen?.clienteActivo||!staff.data?.some(p=>String(p.id)===responsable)}>
        {busy?'Abriendo orden…':'Confirmar apertura'}</button></div>
    </form></section></>;
}
function OrderList(props:Props) {
  const params=new URLSearchParams(props.page.split('?')[1]);
  const page=Math.max(0,Number(params.get('page'))||0),query=params.get('q')??'',status=params.get('estado')??'',vehicle=params.get('vehiculoId')??'';
  const[q,setQ]=useState(query),[estado,setEstado]=useState(status);
  const filters:Record<string,string>={q:query,estado:status,page:String(page),size:'20'};
  if(vehicle)filters.vehiculoId=vehicle;
  const resource=useResource<Pagina<Orden>>('/api/ordenes?'+new URLSearchParams(filters),props.onExpired);
  function search(e:FormEvent){e.preventDefault();props.navigate('/ordenes?'+new URLSearchParams({...filters,q,estado,page:'0'}));}
  return <><div className="page-title"><div><p className="eyebrow">Servicios</p><h1>{props.session.usuario.perfil==='CLIENTE'?'Mis órdenes':'Órdenes del taller'}</h1></div>
    {props.session.usuario.perfil==='PERSONAL'&&<button className="solid" onClick={()=>props.navigate('/ordenes/nueva')}>Abrir orden</button>}</div>
    {vehicle&&<div className="filter-note"><span>Órdenes del vehículo seleccionado</span><button className="quiet" onClick={()=>props.navigate('/ordenes')}>Quitar filtro</button></div>}
    <form className="filters" onSubmit={search}><div className="filter-grid"><div><label htmlFor="buscar-orden">Buscar orden</label>
      <input id="buscar-orden" placeholder="Dominio, cliente o motivo" value={q} maxLength={100} onChange={e=>setQ(e.target.value)}/></div>
      <div><label htmlFor="estado">Estado</label><select id="estado" value={estado} onChange={e=>setEstado(e.target.value)}><option value="">Todos</option>
        {Object.entries(estados).map(([value,label])=><option key={value} value={value}>{label}</option>)}</select></div><button type="submit">Buscar</button></div></form>
    {resource.error?<ErrorBox message={resource.error} retry={resource.reload}/>:resource.loading?<p role="status">Cargando órdenes…</p>:resource.data&&<>
      <p className="result-count">{resource.data.total} {resource.data.total===1?'orden':'órdenes'}</p>
      {resource.data.items.length===0?<div className="empty-state"><h2>No encontramos órdenes</h2><p>{query||status||vehicle?'Probá con otros filtros.':'Los servicios registrados aparecerán en este listado.'}</p></div>:
        <div className="table-wrap"><table><thead><tr><th>Orden</th><th>Vehículo</th><th>Cliente</th><th>Estado</th><th>Ingreso</th></tr></thead>
          <tbody>{resource.data.items.map(o=><tr key={o.id}><td><button className="table-link" onClick={()=>props.navigate('/ordenes/'+o.id)}>#{o.id}</button></td>
            <td><strong>{o.dominio}</strong><small className="cell-secondary">{o.marca} {o.modelo}</small></td><td>{o.clienteNombre}</td>
            <td><span className="badge">{estados[o.estado]??o.estado}</span></td><td className="date-cell">{fecha(o.recibidoEn)}</td></tr>)}</tbody></table></div>}
      <Pager page={page} total={resource.data.total} size={20} move={n=>props.navigate('/ordenes?'+new URLSearchParams({...filters,page:String(n)}))}/>
    </>}</>;
}
function OrderDetail(props:Props) {
  const id=props.page.split('?')[0].split('/').pop();
  const resource=useResource<Orden>('/api/ordenes/'+id,props.onExpired);
  return <><button className="back-link" onClick={()=>props.navigate('/ordenes')}>← Volver a órdenes</button>
    {resource.error?<ErrorBox message={resource.error}/>:resource.loading?<p role="status">Cargando orden…</p>:resource.data&&<>
      <div className="page-title"><div><p className="eyebrow">Servicio del taller</p><h1>Orden #{resource.data.id}</h1></div><span className="badge">{estados[resource.data.estado]??resource.data.estado}</span></div>
      <div className="cards"><section className="panel"><h2>Vehículo</h2><dl className="record-data"><dt>Dominio</dt><dd>{resource.data.dominio}</dd>
        <dt>Marca y modelo</dt><dd>{resource.data.marca} {resource.data.modelo}</dd><dt>Titular</dt><dd>{resource.data.clienteNombre}</dd></dl>
        <button className="text-link quiet" onClick={()=>props.navigate('/vehiculos/'+resource.data!.vehiculoId)}>Ver ficha del vehículo</button></section>
        <section className="panel"><h2>Recepción</h2><dl className="record-data"><dt>Ingreso</dt><dd>{fecha(resource.data.recibidoEn)}</dd>
          <dt>Responsable</dt><dd>{resource.data.responsableNombre}</dd><dt>Actualización</dt><dd>{fecha(resource.data.actualizadoEn)}</dd></dl></section></div>
      <section className="panel form-panel"><h2>Motivo de ingreso</h2><p className="multiline">{resource.data.motivoIngreso}</p></section>
      <Presupuestos ordenId={resource.data.id} perfil={props.session.usuario.perfil} onChanged={resource.reload} onExpired={props.onExpired}/>
      <Ejecucion ordenId={resource.data.id} perfil={props.session.usuario.perfil} onChanged={resource.reload} onExpired={props.onExpired}/>
    </>}</>;
}
