import { useCallback, useEffect, useState } from 'react'
import { Unauthorized } from './api'

export function usePoll<T>(fetcher: () => Promise<T>, intervalMs: number, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setData(await fetcher())
      setError(null)
    } catch (e) {
      if (e instanceof Unauthorized) {
        location.reload()
      } else {
        setError(e instanceof Error ? e.message : String(e))
      }
    }
  }, deps)

  useEffect(() => {
    load()
    const t = setInterval(load, intervalMs)
    return () => clearInterval(t)
  }, [load, intervalMs])

  return { data, error, reload: load }
}
