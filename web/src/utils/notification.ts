import { ref } from 'vue'
import type { NotificationView } from '../api/types'

/**
 * Bumped by any view that changes unread state (mark one / mark all) so the topbar bell refetches
 * immediately instead of waiting for its 30s poll. A tiny shared counter is enough: the bell only
 * needs a nudge, it always asks the server for the authoritative count.
 */
export const notificationRevision = ref(0)

export function bumpNotifications() {
  notificationRevision.value += 1
}

/**
 * The click target of one notification (design §7): ORDER opens its 发布单, ITEM its publish number
 * (falling back to the item id while no ITEM rows exist yet), UNIT the deployment unit, FREEZE the
 * production-control page, WAIVER the gate section of its 发布单.
 *
 * @return the router path, or `null` for an object type this build does not know about — the caller
 *     then keeps the user where they are instead of routing somewhere wrong
 */
export function notificationTarget(n: NotificationView): string | null {
  switch (n.objectType) {
    case 'ORDER':
      return n.objectId ? `/releases/${n.objectId}` : null
    case 'ITEM': {
      // Item rows do not exist yet; a future object_label is the publish number, otherwise the item
      // id is the only thing the route could carry. A row missing both is simply not navigable.
      const target = n.objectLabel || n.objectId
      return target ? `/releases/${target}` : null
    }
    case 'UNIT':
      return n.objectId ? `/units/${n.objectId}` : null
    case 'FREEZE':
      return '/control'
    case 'WAIVER':
      return n.objectLabel ? `/releases/${n.objectLabel}#gate` : null
    default:
      return null
  }
}