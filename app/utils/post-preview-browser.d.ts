export const enabled:boolean
export function read(resource:string,postId:string,params?:Record<string,unknown>):Promise<unknown>
export function subscribe(listener:()=>void):()=>void
export function captureScroll():number
export function restoreScroll(id:number):void
