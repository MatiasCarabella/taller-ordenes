import {useState} from 'react';
import * as api from './api';
export function DescargarPdf({url,nombre,label,onExpired}:{url:string;nombre:string;label:string;onExpired:()=>void}){
  const[busy,setBusy]=useState(false),[error,setError]=useState('');
  async function download(){
    setBusy(true);setError('');
    try{
      const blob=await api.pdf(url),link=document.createElement('a'),objectUrl=URL.createObjectURL(blob);
      link.href=objectUrl;link.download=nombre;document.body.appendChild(link);link.click();link.remove();
      // Leave time for the browser's download process to claim the blob.
      setTimeout(()=>URL.revokeObjectURL(objectUrl),30000);
    }catch(e){
      if(e instanceof api.ApiError&&e.status===401)onExpired();
      else setError(e instanceof api.ApiError?e.message:'No pudimos descargar el documento. Volvé a intentarlo.');
    }finally{setBusy(false);}
  }
  return <div className="pdf-download"><button disabled={busy} onClick={()=>void download()}>{busy?'Preparando PDF…':label}</button>
    {error&&<p className="alert" role="alert">{error}</p>}</div>;
}
