import { request } from '../request';
export interface ReportOption { id:string;kind:'type'|'reason';name:string;sortOrder:number }
export interface ReportOptions { types:ReportOption[];reasons:ReportOption[] }
export interface UserReport { id:string;reporterId:string;reporterName:string;reportedUserId:string;reportedUserName:string;targetKind:'user'|'post';targetId:string;typeName:string;reasonName:string;content:string;createTime:string }
export interface ReportSnapshot { userId:string;nickname:string;signature:string;avatarUrl?:string;postId?:string;title?:string;content?:string; }
export interface ReportDetail { report:UserReport;targetSnapshot:ReportSnapshot;images:string[] }
export const fetchReportOptions=()=>request<ReportOptions>({url:'/report/options'});
export const saveReportOption=(data:Partial<ReportOption>,editing:boolean)=>request<ReportOption>({url:'/report/option/'+(editing?'update':'create'),method:'post',data});
export const deleteReportOption=(id:string)=>request<void>({url:'/report/option/delete',method:'post',data:{id}});
export const fetchReports=(pageNum:number,pageSize:number)=>request<{records:UserReport[];total:number}>({url:'/report/list',params:{pageNum,pageSize}});
export const fetchReportDetail=(id:string)=>request<ReportDetail>({url:'/report/'+id});
