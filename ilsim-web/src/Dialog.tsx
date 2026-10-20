import { type ReactNode, useEffect, useRef } from 'react'

export function Dialog({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => { ref.current?.showModal() }, [])
  return (
    <dialog ref={ref} className="file-dialog" onClose={onClose}
      onClick={(e) => { if (e.target === e.currentTarget) ref.current?.close() }}>
      <div className="file-dialog-body">
        <header>
          <strong>{title}</strong>
          <button className="secondary" onClick={() => ref.current?.close()}>닫기</button>
        </header>
        <div className="file-dialog-content">{children}</div>
      </div>
    </dialog>
  )
}
