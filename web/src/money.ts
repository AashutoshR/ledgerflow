export function pounds(value:string){const [whole,fraction='00']=value.split('.');return '£'+BigInt(whole).toLocaleString('en-GB')+'.'+fraction;}
export function validAmount(value:string){return /^(0|[1-9][0-9]{0,14})\.[0-9]{2}$/.test(value)&&BigInt(value.replace('.',''))>0n;}
