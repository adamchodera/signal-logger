package pl.com.chodera.signallogger.model

/**
 * Picks the cell that actually serves a given SIM out of the list returned by TelephonyManager.
 *
 * Dual-SIM devices sometimes mix the other SIM's cells into the list, so the registered PLMN of the
 * subscription (TelephonyManager.networkOperator) is used to filter and rank candidates.
 */
object ServingCellSelector {

    fun select(cells: List<CellSnapshot>, registeredPlmn: String?): CellSnapshot? {
        // 1. only registered cells can be serving cells
        var candidates: List<CellSnapshot> = cells.filter { it.isRegistered }
        if (candidates.isEmpty()) return null

        // 2. drop cells that provably belong to another operator; keep unknown-PLMN cells (ranked lower)
        if (registeredPlmn != null) {
            candidates = candidates.filter { it.plmn == null || it.plmn == registeredPlmn }
            if (candidates.isEmpty()) return null
        }

        // 3. rank: PLMN match, then primary serving, then RAT order (LTE anchor wins in NSA)
        val comparator: Comparator<CellSnapshot> = compareBy<CellSnapshot>(
            { if (registeredPlmn != null && it.plmn == registeredPlmn) 0 else 1 },
            { if (it.isPrimaryServing) 0 else 1 },
            { ratRank(it.rat) },
        )
        // minWithOrNull keeps the first element among equals, so the result is deterministic.
        return candidates.minWithOrNull(comparator)
    }

    private fun ratRank(rat: Rat): Int = when (rat) {
        Rat.LTE -> 0
        Rat.NR -> 1
        Rat.WCDMA -> 2
        Rat.GSM -> 3
        Rat.OTHER -> 4
    }
}
