declare namespace Api {
  namespace Publish {
    interface Rule { mode: 'image'|'video'|'post'; titleMin:number;titleMax:number;contentMin:number;contentMax:number;imageMax:number;videoMax:number;totalMediaMax:number;maxVideoMb:number }
    interface Policy { defaultValue:boolean;userEditable:boolean }
    interface Settings { reviewRequired:boolean;allowDownload:Policy;downloadWatermark:Policy;original:Policy;featureFlags:Record<string,boolean>;pollMinOptions:number;pollMaxOptions:number;commentImageMax:number;commentDeleteCascade:boolean }
    interface Topic { id?:number;name:string;description:string;coverUrl:string;status:number;recommended:boolean;hot:boolean;enabled:boolean;sortOrder:number;creatorId?:number;viewCount:number }
    interface Group { id?:number;name:string;sortOrder:number;enabled:boolean }
    interface Emoji { id?:number;groupId:number;name:string;imageUrl:string;sortOrder:number;enabled:boolean }
    interface Color { id?:number;name:string;colorValue:string;sortOrder:number;enabled:boolean }
    interface Divider { id?:number;name:string;previewText:string;sortOrder:number;enabled:boolean }
    interface Health { ffmpeg:{available:boolean;message:string;ffmpegPath:string;ffprobePath:string};redis:{available:boolean;message:string} }
  }
}
