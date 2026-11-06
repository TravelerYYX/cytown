export function postChatWorker(worker:ServiceWorker,payload:unknown,ports?:MessagePort[]):void;
export function refreshChatWorker(owner:string,token:string):void;
export function createChatVideo(host:HTMLElement,source:string,failed:()=>void):{pause():void;play():void;destroy():void};
