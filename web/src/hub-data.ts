import {seedProposals,type Proposal} from './proposal-data';
export type ClientRecord={id:string;name:string;company:string;email:string};
export type ProjectRecord={id:string;clientId:string|null;name:string;description:string;dueDate:string};
export type TaskRecord={id:string;projectId:string;title:string;status:'TODO'|'DOING'|'DONE';version:number};
export type ActivityRecord={id:string;message:string;createdAt:string};
export type HubData={proposals?:Proposal[];clients:ClientRecord[];projects:ProjectRecord[];tasks:TaskRecord[];activity:ActivityRecord[]};
export const emptyHub:HubData={clients:[],projects:[],tasks:[],activity:[]};
export function seedHub():HubData {return {proposals:seedProposals(),
clients:[{id:'c1',name:'Olivia Bennett',company:'Maison & Co.',email:'olivia@example.test'},{id:'c2',name:'James Chen',company:'Forma Studio',email:'james@example.test'},{id:'c3',name:'Amara Wilson',company:'North Collective',email:'amara@example.test'}],
projects:[{id:'p1',clientId:'c1',name:'A new perspective',description:'A considered brand experience for Maison & Co. From first impression to the final detail.',dueDate:'2026-11-20'},{id:'p2',clientId:'c2',name:'Spaces for tomorrow',description:'Digital direction and launch planning for a new generation of creative spaces.',dueDate:'2026-12-04'},{id:'p3',clientId:'c3',name:'The winter collection',description:'Campaign planning, creative production and a thoughtful seasonal launch.',dueDate:'2026-12-12'}],
tasks:[{id:'t1',projectId:'p1',title:'Define the creative direction',status:'DONE',version:0},{id:'t2',projectId:'p1',title:'Design the first experience',status:'DOING',version:0},{id:'t3',projectId:'p1',title:'Prepare the client presentation',status:'TODO',version:0},{id:'t4',projectId:'p2',title:'Map the launch journey',status:'DOING',version:0},{id:'t5',projectId:'p2',title:'Review the project brief',status:'DONE',version:0},{id:'t6',projectId:'p3',title:'Gather campaign references',status:'TODO',version:0}],
activity:[{id:'a1',message:'Demo workspace ready to explore',createdAt:new Date().toISOString()}]};}
export function progress(tasks:TaskRecord[],id:string){const group=tasks.filter(t=>t.projectId===id);return group.length?Math.round(group.filter(t=>t.status==='DONE').length/group.length*100):0;}
export function searchHub(data:HubData,query:string){const term=query.trim().toLowerCase();return {clients:data.clients.filter(c=>(c.name+' '+c.company+' '+c.email).toLowerCase().includes(term)),projects:data.projects.filter(p=>(p.name+' '+p.description).toLowerCase().includes(term)),tasks:data.tasks.filter(t=>t.title.toLowerCase().includes(term))};}
