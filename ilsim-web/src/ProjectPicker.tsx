import { useEffect, useState } from 'react'
import type { Project } from './api'
import { ago } from './taskView'

const KEY = 'ilsim.project'

function load(): string {
  try { return localStorage.getItem(KEY) ?? '' } catch { return '' }
}

/** 화면 사이에서 공유하는 선택 프로젝트. 고른 값은 이 브라우저에 기억하고, 없거나 사라졌으면 연결된 프로젝트를 고른다. */
export function useSelectedProject(projects: Project[] | null) {
  const [picked, setPicked] = useState(load)
  useEffect(() => {
    try { localStorage.setItem(KEY, picked) } catch { /* 저장소가 막혀도 동작한다 */ }
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
