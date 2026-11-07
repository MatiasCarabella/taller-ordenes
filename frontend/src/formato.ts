export const fecha=(s:string)=>new Intl.DateTimeFormat('es-AR',{dateStyle:'short',timeStyle:'short',timeZone:'America/Argentina/Buenos_Aires',hourCycle:'h23'}).format(new Date(s));
// Derived labor totals can exceed the safe cent precision of Number.
export function ars(value:string){
  const negative=value.startsWith('-'),[whole,fraction='00']=value.replace(/^-/, '').split('.');
  return 'ARS '+(negative?'-':'')+new Intl.NumberFormat('es-AR',{maximumFractionDigits:0}).format(BigInt(whole))+','+fraction.padEnd(2,'0').slice(0,2);
}
export const percent=(s:string|null)=>s===null?'No aplica':s.replace('.',',')+' %';
