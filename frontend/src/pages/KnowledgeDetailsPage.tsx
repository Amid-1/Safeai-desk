import {useCallback, useEffect, useState} from 'react'
import {Link, useParams} from 'react-router-dom'
import {getKnowledgeBase} from '../api/knowledgeApi'
import type {KnowledgeBase} from '../api/knowledgeApi'
import {
    getKnowledgeDocuments,
    knowledgeDocumentDownloadUrl,
    uploadKnowledgeDocument,
    uploadKnowledgeDocumentVersion
} from '../api/knowledgeDocumentApi'
import type {KnowledgeDocument} from '../api/knowledgeDocumentApi'
import {getApiErrorMessage} from '../api/http'
import {formatDateTime} from '../utils/format'
import {EmptyState, ErrorState, LoadingState} from '../components/StateBlock'
import Modal from '../components/Modal'
import './KnowledgeDetailsPage.css'

const MAX_FILE_SIZE = 25 * 1024 * 1024
const ACCEPT = '.pdf,.docx,.txt,.html,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,text/plain,text/html'
const statusLabel: Record<string, string> = {
    PENDING: 'Ожидает обработки',
    VALIDATING: 'Проверяется',
    EXTRACTING: 'Обрабатывается',
    READY: 'Готов',
    FAILED: 'Ошибка обработки'
}
type UploadTarget = KnowledgeDocument | 'new' | null

function KnowledgeDetailsPage() {
    const {knowledgeBaseId = ''} = useParams()
    const [base, setBase] = useState<KnowledgeBase | null>(null)
    const [documents, setDocuments] = useState<KnowledgeDocument[]>([])
    const [loading, setLoading] = useState(true)
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState('')
    const [uploadTarget, setUploadTarget] = useState<UploadTarget>(null)
    const [uploadName, setUploadName] = useState('')
    const [uploadFile, setUploadFile] = useState<File | null>(null)
    const [uploadError, setUploadError] = useState('')

    const load = useCallback(async (signal?: AbortSignal) => {
        const [kb, page] = await Promise.all([getKnowledgeBase(knowledgeBaseId, {signal}), getKnowledgeDocuments(knowledgeBaseId, 0, 100, signal)])
        setBase(kb)
        setDocuments(page.content)
    }, [knowledgeBaseId])

    useEffect(() => {
        const controller = new AbortController()
        setLoading(true)
        load(controller.signal).catch((loadError) => {
            if (!controller.signal.aborted) setError(getApiErrorMessage(loadError, 'Не удалось открыть базу знаний.'))
        }).finally(() => {
            if (!controller.signal.aborted) setLoading(false)
        })
        return () => controller.abort()
    }, [load])

    function openUpload(target: Exclude<UploadTarget, null>) {
        setUploadTarget(target)
        setUploadFile(null)
        setUploadError('')
        setUploadName(target === 'new' ? '' : target.name)
    }

    function closeUpload() {
        if (busy) return
        setUploadTarget(null)
        setUploadFile(null)
        setUploadError('')
    }

    function selectFile(file: File | null) {
        setUploadError('')
        setUploadFile(file)
        if (file && uploadTarget === 'new' && !uploadName.trim()) setUploadName(file.name.replace(/\.[^.]+$/, ''))
        if (file && file.size > MAX_FILE_SIZE) setUploadError('Размер файла превышает 25 МБ.')
    }

    async function submitUpload() {
        if (!uploadFile || !uploadTarget) {
            setUploadError('Выберите файл.');
            return
        }
        if (uploadFile.size > MAX_FILE_SIZE) return
        if (uploadTarget === 'new' && !uploadName.trim()) {
            setUploadError('Введите название документа.');
            return
        }
        setBusy(true)
        setUploadError('')
        try {
            if (uploadTarget === 'new') await uploadKnowledgeDocument(knowledgeBaseId, uploadFile, uploadName)
            else await uploadKnowledgeDocumentVersion(knowledgeBaseId, uploadTarget.id, uploadFile)
            await load()
            setUploadTarget(null)
            setUploadFile(null)
        } catch (uploadFailure) {
            setUploadError(getApiErrorMessage(uploadFailure, 'Не удалось загрузить файл.'))
        } finally {
            setBusy(false)
        }
    }

    if (loading) return <LoadingState message="Загрузка базы знаний..."/>
    if (!base) return <ErrorState message={error || 'База знаний не найдена.'}/>

    return <div className="page knowledge-details">
        <Link to="/knowledge" className="knowledge-details__back">← Базы знаний</Link>
        <header className="knowledge-details__header">
            <div><h1>{base.name}</h1><p
                className="muted">{base.description ?? 'Корпоративные документы и инструкции.'}</p></div>
            <button type="button" disabled={!base.enabled} onClick={() => openUpload('new')}>Загрузить документ</button>
        </header>
        {!base.enabled &&
            <div className="knowledge-disabled-note">База отключена. Обычные пользователи не видят её и не могут читать
                документы.</div>}
        {error &&
            <ErrorState message={error} action={<button type="button" onClick={() => void load()}>Повторить</button>}/>}
        {!error && documents.length === 0 && <EmptyState title="Документов пока нет"
                                                         message="Загрузите первый корпоративный документ. Поддерживаются PDF, DOCX, TXT и HTML до 25 МБ."/>}
        {documents.length > 0 && <div className="table-wrapper">
            <table className="knowledge-documents">
                <thead>
                <tr>
                    <th>Название</th>
                    <th>Версия</th>
                    <th>Статус</th>
                    <th>Размер</th>
                    <th>Обновлён</th>
                    <th>Действия</th>
                </tr>
                </thead>
                <tbody>{documents.map((document) => <tr key={document.id}>
                    <td><strong>{document.name}</strong><small>{document.originalFilename}</small></td>
                    <td>{document.versionNumber ?? '—'}</td>
                    <td><span
                        className={`document-status document-status--${(document.status ?? 'PENDING').toLowerCase()}`}>{statusLabel[document.status ?? 'PENDING']}</span>
                    </td>
                    <td>{formatBytes(document.sizeBytes)}</td>
                    <td>{formatDateTime(document.updatedAt)}</td>
                    <td>
                        <div className="document-actions"><a className="secondary-button"
                                                             href={knowledgeDocumentDownloadUrl(knowledgeBaseId, document.id)}>Скачать</a>
                            <button type="button" className="secondary-button" disabled={busy || !base.enabled}
                                    onClick={() => openUpload(document)}>Новая версия
                            </button>
                        </div>
                    </td>
                </tr>)}</tbody>
            </table>
        </div>}
        {uploadTarget && <Modal title={uploadTarget === 'new' ? 'Загрузка документа' : 'Новая версия документа'}
                                onClose={closeUpload}>
            <div className="knowledge-upload-form">
                {uploadTarget === 'new' &&
                    <label>Название документа<input value={uploadName} maxLength={255} disabled={busy}
                                                    onChange={(event) => setUploadName(event.target.value)}/></label>}
                {uploadTarget !== 'new' &&
                    <p className="knowledge-upload-target"><strong>{uploadTarget.name}</strong><span>Будет создана версия {(uploadTarget.versionNumber ?? 0) + 1}. Предыдущая версия сохранится.</span>
                    </p>}
                <label className="knowledge-file-picker">Файл<input type="file" accept={ACCEPT} disabled={busy}
                                                                    onChange={(event) => selectFile(event.target.files?.[0] ?? null)}/></label>
                <p className="knowledge-upload-hint">PDF, DOCX, TXT или HTML, не более 25 МБ.</p>
                {uploadFile && <div className="knowledge-selected-file">
                    <strong>{uploadFile.name}</strong><span>{formatBytes(uploadFile.size)}</span></div>}
                {uploadError && <div className="error" role="alert">{uploadError}</div>}
                <div className="knowledge-upload-actions">
                    <button type="button" className="secondary-button" disabled={busy} onClick={closeUpload}>Отмена
                    </button>
                    <button type="button" disabled={busy || !uploadFile || Boolean(uploadError)}
                            onClick={() => void submitUpload()}>{busy ? 'Загрузка...' : 'Загрузить'}</button>
                </div>
            </div>
        </Modal>}
    </div>
}

function formatBytes(value: number) {
    if (value < 1024) return `${value} Б`;
    if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} КБ`;
    return `${(value / 1024 / 1024).toFixed(1)} МБ`
}

export default KnowledgeDetailsPage
