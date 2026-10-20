import { useEffect, useState } from 'react'
import type { Project } from './api'
import { ago } from './taskView'

const KEY = 'ilsim.project'

function load(): string {
  try { return localStorage.getItem(KEY) ?? '' } catch { return '' }
}

export function useSelectedProject(projects: Project[] | null) {
  const [picked, setPicked] = useState(load)
  useEffect(() => {
    try { localStorage.setItem(KEY, picked) } catch { }
  }, [picked])
  const valid = projects?.some((p) => p.id === picked) ? picked : ''
  const projectId = valid || projects?.find((p) => p.online)?.id || projects?.[0]?.id || ''
  return [projectId, setPicked] as const
}

export function ProjectPicker({ projects, value, onChange }: {
  projects: Project[] | null; value: string; onChange: (id: string) => void
}) {
  if (!projects?.length) return null
  const current = projects.find((p) => p.id === value)
  return (
    <label className="picker" title={current ? (current.online ? 'Runner 연결됨' : `Runner 오프라인 · 마지막 ${ago(current.lastSeen)}`) : undefined}>
      <span className={`presence ${current?.online ? 'on' : 'off'}`} aria-hidden />
      <select value={value} onChange={(e) => onChange(e.target.value)} aria-label="프로젝트">
        {projects.map((p) => (
          <option key={p.id} value={p.id}>{p.name}{p.online ? '' : ' (오프라인)'}</option>
        ))}
      </select>
    </label>
  )
}
