declare global {interface Window {__LEDGERFLOW_CONFIG__?:{oidcAuthority?:string;demoEnabled?:boolean}}}
import { UserManager, WebStorageStateStore } from 'oidc-client-ts';
export const auth=new UserManager({authority:window.__LEDGERFLOW_CONFIG__?.oidcAuthority||import.meta.env.VITE_OIDC_AUTHORITY||'http://localhost:8180/realms/ledgerflow',client_id:'ledgerflow-web',redirect_uri:window.location.origin+'/callback',post_logout_redirect_uri:window.location.origin,response_type:'code',scope:'openid profile email',userStore:new WebStorageStateStore({store:window.sessionStorage}),automaticSilentRenew:true});
export class ApiError extends Error { constructor(message:string,public status:number){super(message);} }
export async function api<T>(path:string,body?:unknown,key?:string,method?:string):Promise<T>{const user=await auth.getUser();if(!user||user.expired)throw new Error('Your session has expired. Please sign in again.');const response=await fetch('/api/v1'+path,{method:method||(body?'POST':'GET'),headers:{Authorization:'Bearer '+user.access_token,'Content-Type':'application/json',...(key?{'Idempotency-Key':key}:{})},body:body?JSON.stringify(body):undefined});if(!response.ok){const error=await response.json().catch(()=>({}));throw new ApiError((error.code||'Service unavailable')+(error.requestId?' · Reference '+error.requestId:''),response.status);}return response.status===204||response.headers.get("content-length")==="0"?undefined as T:response.text().then(text=>text?JSON.parse(text):undefined);}
export type Wallet={id:string,balance:string,currency:string};
export type Transaction={operationId:string,kind:string,amount:string,occurredAt:string};
export type Transfer={id:string,status:string,amount:string,errorCode?:string};

export const demoEnabled=window.__LEDGERFLOW_CONFIG__?.demoEnabled??(import.meta.env.VITE_DEMO_ENABLED==='true');
