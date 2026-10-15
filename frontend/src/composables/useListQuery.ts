import { reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'

type QueryValue = string | number

export function useListQuery<T extends Record<string, QueryValue>>(defaults: T) {
  const route = useRoute()
  const router = useRouter()
  const initial = { ...defaults } as T
  for (const [key, fallback] of Object.entries(defaults)) {
    const value = route.query[key]
    if (typeof value !== 'string' || value === '') continue
    ;(initial as Record<string, QueryValue>)[key] = typeof fallback === 'number'
      ? Math.max(1, Number(value) || fallback)
      : value
  }
  const query = reactive(initial) as T

  async function syncQuery() {
    const next = Object.fromEntries(Object.entries(query)
      .filter(([, value]) => value !== '' && value !== undefined)
      .map(([key, value]) => [key, String(value)]))
    await router.replace({ query: next })
  }

  return { query, syncQuery }
}
