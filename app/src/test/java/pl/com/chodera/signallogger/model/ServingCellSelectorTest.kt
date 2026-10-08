package pl.com.chodera.signallogger.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ServingCellSelectorTest {

    private fun cell(
        rat: Rat = Rat.LTE,
        registered: Boolean = true,
        primary: Boolean = false,
        mcc: String? = "260",
        mnc: String? = "03",
        cellId: Long? = 1L,
    ) = CellSnapshot(
        rat = rat,
        isRegistered = registered,
        isPrimaryServing = primary,
        mcc = mcc,
        mnc = mnc,
        cellId = cellId,
        tac = 100,
        pci = 200,
        arfcn = 1300,
        bands = listOf(3),
        ageS = 0.5,
    )

    @Test
    fun picksPlmnMatchOverForeignRegisteredCell() {
        val foreign = cell(mcc = "260", mnc = "06", cellId = 1, primary = true)
        val own = cell(mcc = "260", mnc = "03", cellId = 2)

        val result = ServingCellSelector.select(listOf(foreign, own), "26003")

        assertSame(own, result)
    }

    @Test
    fun dropsForeignCellEvenWhenItIsTheOnlyRegisteredOne() {
        val foreign = cell(mcc = "260", mnc = "06")

        assertNull(ServingCellSelector.select(listOf(foreign), "26003"))
    }

    @Test
    fun plmnMatchBeatsUnknownPlmnEvenIfUnknownIsPrimary() {
        val unknown = cell(mcc = null, mnc = null, primary = true, cellId = 1)
        val own = cell(cellId = 2)

        assertSame(own, ServingCellSelector.select(listOf(unknown, own), "26003"))
    }

    @Test
    fun prefersPrimaryServing() {
        val secondary = cell(cellId = 1)
        val primary = cell(cellId = 2, primary = true)

        assertSame(primary, ServingCellSelector.select(listOf(secondary, primary), "26003"))
    }

    @Test
    fun primaryServingNrBeatsNonPrimaryLte() {
        val lte = cell(rat = Rat.LTE, cellId = 1)
        val nr = cell(rat = Rat.NR, cellId = 2, primary = true)

        assertSame(nr, ServingCellSelector.select(listOf(lte, nr), "26003"))
    }

    @Test
    fun prefersLteOverNrWhenNeitherIsPrimary() {
        val nr = cell(rat = Rat.NR, cellId = 1)
        val lte = cell(rat = Rat.LTE, cellId = 2)

        assertSame(lte, ServingCellSelector.select(listOf(nr, lte), "26003"))
    }

    @Test
    fun ratOrderIsLteNrWcdmaGsmOther() {
        val cells = listOf(Rat.OTHER, Rat.GSM, Rat.WCDMA, Rat.NR, Rat.LTE).map { cell(rat = it) }

        assertEquals(Rat.LTE, ServingCellSelector.select(cells, "26003")?.rat)
        assertEquals(Rat.NR, ServingCellSelector.select(cells.filter { it.rat != Rat.LTE }, "26003")?.rat)
        assertEquals(Rat.WCDMA, ServingCellSelector.select(cells.filter { it.rat == Rat.WCDMA || it.rat == Rat.GSM }, "26003")?.rat)
    }

    @Test
    fun unknownPlmnSingleCellIsReturned() {
        val unknown = cell(mcc = null, mnc = null)

        assertSame(unknown, ServingCellSelector.select(listOf(unknown), "26003"))
    }

    @Test
    fun withoutRegisteredPlmnNothingIsFilteredByOperator() {
        val a = cell(mcc = "260", mnc = "06", cellId = 1, primary = true)
        val b = cell(mcc = "260", mnc = "03", cellId = 2)

        assertSame(a, ServingCellSelector.select(listOf(b, a), null))
    }

    @Test
    fun emptyListReturnsNull() {
        assertNull(ServingCellSelector.select(emptyList(), "26003"))
    }

    @Test
    fun noRegisteredCellReturnsNull() {
        val neighbour = cell(registered = false)

        assertNull(ServingCellSelector.select(listOf(neighbour), "26003"))
    }

    @Test
    fun plmnIsConcatenationOfMccAndMnc() {
        assertEquals("26003", cell().plmn)
        assertNull(cell(mnc = null).plmn)
    }
}
