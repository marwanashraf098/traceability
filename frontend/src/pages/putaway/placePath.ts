import type { TFunction } from 'i18next'
import type { PlaceRef } from '../../api'

/**
 * How a place reads everywhere outside Settings › Places: a shelf as its trail (Z1 › R01 › S2), a box as
 * "BOX-12 on Z1-R01-S2" / "BOX-12 in Z1" (a box keeps its own code when it moves, so its parent is named in full).
 */
export function placePath(place: Pick<PlaceRef, 'kind' | 'code' | 'trail' | 'parentCode' | 'parentKind'>, t: TFunction): string {
  if (place.kind === 'box') {
    if (!place.parentCode) return place.code
    return t(place.parentKind === 'shelf' ? 'putAway.path.boxOn' : 'putAway.path.boxIn',
      { box: place.code, parent: place.parentCode })
  }
  return place.trail.length > 0 ? place.trail.join(' › ') : place.code
}
