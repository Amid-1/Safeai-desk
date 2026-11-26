// frontend/src/app/DataManagementLayout.test.tsx
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import DataManagementLayout from './DataManagementLayout'
const auth=vi.hoisted(()=>({currentUser:{id:'actor',organizationId:'tenant',roles:['ADMIN']}}))
const access=vi.hoisted(()=>({failed:false}))
const caps=vi.hoisted(()=>({platform:false,tenantRegistry:true,reviewQueue:false,personalRequests:true,requestsEnabled:true,approvalsEnabled:false}))
vi.mock('../features/auth/state/useAuth',()=>({useAuth:()=>auth}))
vi.mock('../features/erasure/api/useWorkspaceCapabilities',()=>({useWorkspaceCapabilities:()=>({capabilities:access.failed?null:caps,failed:access.failed,loading:false,retry:vi.fn()})}))
function mount(path:string){return render(<MemoryRouter initialEntries={[path]}><Routes><Route element={<DataManagementLayout/>}><Route path="*" element={<p>Рабочий раздел</p>}/></Route></Routes></MemoryRouter>)}
describe('Data navigation',()=>{
    it('hides global policy and ungranted review from tenant',()=>{auth.currentUser.roles=['ADMIN'];caps.platform=false;caps.tenantRegistry=true;caps.reviewQueue=false;mount('/admin/erasure/intake');expect(screen.getByRole('link',{name:'Заявки'})).toHaveAttribute('aria-current','page');expect(screen.getByRole('link',{name:'Обзор'})).toHaveAttribute('href','/admin/erasure');expect(screen.queryByRole('link',{name:'Политика хранения'})).not.toBeInTheDocument();expect(screen.queryByRole('link',{name:'Согласования'})).not.toBeInTheDocument()})
    it('shows read-only platform registry without granting review',()=>{auth.currentUser.roles=['SUPER_ADMIN'];caps.platform=true;caps.tenantRegistry=false;caps.reviewQueue=false;mount('/admin/erasure');expect(screen.getByRole('link',{name:'Обзор'})).toHaveAttribute('aria-current','page');expect(screen.getByRole('link',{name:'Заявки'})).toHaveAttribute('href','/admin/erasure/registry');expect(screen.queryByRole('link',{name:'Согласования'})).not.toBeInTheDocument()})
    it('keeps read-only destinations visible but noninteractive when access cannot be verified',()=>{auth.currentUser.roles=['SUPER_ADMIN'];access.failed=true;try{mount('/admin/erasure');expect(screen.getByText('Заявки')).toHaveAttribute('aria-disabled','true');expect(screen.getByText('История изменений')).toHaveAttribute('aria-disabled','true');expect(screen.queryByRole('link',{name:'Заявки'})).not.toBeInTheDocument();expect(screen.queryByRole('link',{name:'Согласования'})).not.toBeInTheDocument()}finally{access.failed=false}})
    it('keeps tenant overview and disabled registry visible when intake is off',()=>{
        auth.currentUser.roles=['ADMIN'];caps.platform=false;caps.tenantRegistry=false;caps.reviewQueue=false;caps.requestsEnabled=false
        try {
            mount('/admin/erasure')
            expect(screen.getByRole('link',{name:'Обзор'})).toHaveAttribute('aria-current','page')
            expect(screen.getByText('Заявки')).toHaveAttribute('aria-disabled','true')
            expect(screen.queryByRole('link',{name:'Заявки'})).not.toBeInTheDocument()
            expect(screen.queryByRole('link',{name:'Согласования'})).not.toBeInTheDocument()
            expect(screen.queryByRole('link',{name:'Политика хранения'})).not.toBeInTheDocument()
        } finally { caps.requestsEnabled=true;caps.tenantRegistry=true }
    })
    it('shows queue only for a granted reviewer',()=>{auth.currentUser.roles=['ADMIN'];caps.platform=false;caps.tenantRegistry=true;caps.reviewQueue=true;mount('/admin/erasure/requests');expect(screen.getByRole('link',{name:'Согласования'})).toHaveAttribute('aria-current','page')})
})
