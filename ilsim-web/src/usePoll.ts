import { useCallback, useEffect, useState } from 'react'
import { Unauthorized } from './api'

/** 주기적으로 fetcher를 호출한다. 인증 실패(세션 만료) 시 새로고침해 로그인 화면으로 돌아간다. */
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)

  useEffect(() => {
    load()
    const t = setInterval(load, intervalMs)
    return () => clearInterval(t)
  }, [load, intervalMs])

  return { data, error, reload: load }
}
