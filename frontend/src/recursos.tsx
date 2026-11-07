import { useEffect, useState } from 'react';
import * as api from './api';

export function useResource<T>(url:string|null,onExpired:()=>void) {
  const[data,setData]=useState<T|null>(null),[loading,setLoading]=useState(!!url),[error,setError]=useState(''),[version,setVersion]=useState(0);
  useEffect(()=>{
    if(!url)return;
    let alive=true;setLoading(true);setError('');
    api.get<T>(url).then(value=>{if(alive)setData(value);}).catch(e=>{
      if(!alive)return;if(e instanceof api.ApiError&&e.status===401)onExpired();else setError(e instanceof api.ApiError?e.message:'No pudimos conectar. Volvé a intentarlo.');
    }).finally(()=>{if(alive)setLoading(false);});
    return()=>{alive=false;};
  },[url,version,onExpired]);
  return {data,loading,error,reload:()=>setVersion(v=>v+1)};
}
export function ErrorBox({message,retry}:{message:string;retry?:()=>void}) {
  return <div className="alert" role="alert"><p>{message}</p>{retry&&<button onClick={retry}>Volver a intentar</button>}</div>;
}
