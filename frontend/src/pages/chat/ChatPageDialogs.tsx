// ============================================================
// frontend/src/pages/chat/ChatPageDialogs.tsx
// ============================================================
import type { ChatPageController } from './useChatPageController'
import ChatTitleEditor from '../../components/chat/ChatTitleEditor'
import Modal from '../../components/Modal'
import ConfirmDialog from '../../components/ConfirmDialog'

type ChatPageDialogsProps = {
    controller: Pick<ChatPageController,
    | 'chatToRename'
    | 'renamingChatId'
    | 'setChatToRename'
    | 'handleRenameFromHistory'
    | 'chatToArchive'
    | 'archivingChatId'
    | 'setChatToArchive'
    | 'handleArchiveChat'
    | 'selectedTrustItem'
    | 'setSelectedTrustItem'
>
}

export default function ChatPageDialogs({ controller }: ChatPageDialogsProps) {
    const {
        chatToRename,
        renamingChatId,
        setChatToRename,
        handleRenameFromHistory,
        chatToArchive,
        archivingChatId,
        setChatToArchive,
        handleArchiveChat,
        selectedTrustItem,
        setSelectedTrustItem,
    } = controller

    return (
        <>
            {chatToRename && (
                <Modal
                    title="Переименовать чат"
                    size="sm"
                    onClose={() => setChatToRename(null)}
                    closeDisabled={renamingChatId === chatToRename.id}
                    closeOnBackdrop={renamingChatId !== chatToRename.id}
                >
                    <p className="chat-rename-description">
                        Изменится только название чата. Сообщения и история
                        запросов сохранятся.
                    </p>
                    <ChatTitleEditor
                        key={chatToRename.id}
                        title={chatToRename.title}
                        onSave={handleRenameFromHistory}
                        initiallyEditing
                        onSaved={() => setChatToRename(null)}
                        onCancelEditing={() => setChatToRename(null)}
                    />
                </Modal>
            )}

            {chatToArchive && (
                <ConfirmDialog
                    title="Убрать чат из списка?"
                    message={
                        `Чат «${chatToArchive.title}» исчезнет из рабочего списка. `
                        + 'История, стоимость и доказательная цепочка сохранятся для аудита.'
                    }
                    confirmText="Убрать чат"
                    danger
                    loading={archivingChatId === chatToArchive.id}
                    onCancel={() => setChatToArchive(null)}
                    onConfirm={() => handleArchiveChat(chatToArchive)}
                />
            )}

            {selectedTrustItem && (
                <Modal
                    title={selectedTrustItem.title}
                    size="sm"
                    descriptionId="chat-trust-description"
                    onClose={() => setSelectedTrustItem(null)}
                >
                    <div className="chat-trust-dialog">
                        <div className="chat-trust-dialog__icon" aria-hidden="true">
                            {selectedTrustItem.icon}
                        </div>
                        <p id="chat-trust-description">
                            {selectedTrustItem.description}
                        </p>
                        <ul>
                            {selectedTrustItem.checks.map((check) => (
                                <li key={check}>{check}</li>
                            ))}
                        </ul>
                        <button
                            type="button"
                            onClick={() => setSelectedTrustItem(null)}
                        >
                            Понятно
                        </button>
                    </div>
                </Modal>
            )}
        </>
    )
}
