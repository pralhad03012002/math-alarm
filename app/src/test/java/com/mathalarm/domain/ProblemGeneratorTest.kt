package com.mathalarm.domain

import com.mathalarm.data.Difficulty
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every generator rule in BUILD_SPECS §3 / BRAINSTORM §4.2, pinned.
 *
 * The tier tests assert the structural property **directly** — "this addition's units digits sum to
 * at least ten", "this division's dividend is a multiple of its divisor" — rather than only asking
 * [ProblemGenerator.isWellFormed], so that they would still fail if the validator and the generator
 * were wrong in the same direction. `isWellFormed` is then pinned separately against hand-built bad
 * problems, so it is not a tautology either.
 *
 * Sample sizes are deliberately large: these are rejection-free constructive generators, so a rule
 * that holds over several thousand draws from a sweep of seeds holds.
 */
class ProblemGeneratorTest {

    private val draws = 2_000

    private fun sample(difficulty: Difficulty, count: Int = draws): List<Problem> {
        val random = Random(20261009L)
        return List(count) { ProblemGenerator.generate(difficulty, random) }
    }

    // ------------------------------------------------------------------- easy

    /** EASY `a + b`, a,b ∈ 2..9, and the "no identical operands at EASY" rule. */
    @Test
    fun `easy addition stays in two through nine and never repeats an operand`() {
        val additions = sample(Difficulty.EASY).filter { it.operation == Operation.ADD }
        assertTrue("no easy additions were generated", additions.isNotEmpty())
        additions.forEach {
            val (a, b) = it.operands
            assertTrue("$it: a out of 2..9", a in 2..9)
            assertTrue("$it: b out of 2..9", b in 2..9)
            assertFalse("$it: identical operands are forbidden at EASY", a == b)
            assertEquals(a + b, it.answer)
        }
    }

    /** EASY `a - b`, a ∈ 5..18, b < a, result > 0, and no degenerate `- 0` / `- 1`. */
    @Test
    fun `easy subtraction is positive and never subtracts zero or one`() {
        val subtractions = sample(Difficulty.EASY).filter { it.operation == Operation.SUBTRACT }
        assertTrue("no easy subtractions were generated", subtractions.isNotEmpty())
        subtractions.forEach {
            val (a, b) = it.operands
            assertTrue("$it: a out of 5..18", a in 5..18)
            assertTrue("$it: b must be at least 2", b >= 2)
            assertTrue("$it: b must be smaller than a", b < a)
            assertTrue("$it: result must be positive", it.answer > 0)
            assertEquals(a - b, it.answer)
        }
    }

    /** EASY offers only the two forms the spec lists — no multiplication sneaks in. */
    @Test
    fun `easy only ever produces addition or subtraction`() {
        val operations = sample(Difficulty.EASY).map { it.operation }.toSet()
        assertEquals(setOf(Operation.ADD, Operation.SUBTRACT), operations)
    }

    // ----------------------------------------------------------------- medium

    /**
     * MEDIUM two-digit addition **with a carry** — the rule verified by actual carry presence, not
     * by digit count.
     */
    @Test
    fun `every medium addition is two-digit and actually carries`() {
        val additions = sample(Difficulty.MEDIUM).filter { it.operation == Operation.ADD }
        assertTrue("no medium additions were generated", additions.isNotEmpty())
        additions.forEach {
            val (a, b) = it.operands
            assertTrue("$it: a is not two-digit", a in 10..99)
            assertTrue("$it: b is not two-digit", b in 10..99)
            assertTrue("$it: units digits must carry", (a % 10) + (b % 10) >= 10)
            assertEquals(a + b, it.answer)
        }
    }

    /** BRAINSTORM §4.2's own counter-example: `20 + 30` is two-digit but it is not MEDIUM. */
    @Test
    fun `twenty plus thirty is not a medium problem`() {
        assertFalse(ProblemGenerator.hasCarry(20, 30))
        assertFalse(
            ProblemGenerator.isWellFormed(
                Problem(Operation.ADD, listOf(20, 30), 50),
                Difficulty.MEDIUM,
            ),
        )
    }

    /** MEDIUM two-digit subtraction **with a borrow**, result positive. */
    @Test
    fun `every medium subtraction is two-digit, borrows, and stays positive`() {
        val subtractions = sample(Difficulty.MEDIUM).filter { it.operation == Operation.SUBTRACT }
        assertTrue("no medium subtractions were generated", subtractions.isNotEmpty())
        subtractions.forEach {
            val (a, b) = it.operands
            assertTrue("$it: a is not two-digit", a in 10..99)
            assertTrue("$it: b is not two-digit", b in 10..99)
            assertTrue("$it: units digit must borrow", (a % 10) < (b % 10))
            assertTrue("$it: result must be positive", it.answer > 0)
            assertEquals(a - b, it.answer)
        }
    }

    /** The borrow twin of the `20 + 30` case: `50 - 30` needs no borrow, so it is not MEDIUM. */
    @Test
    fun `fifty minus thirty is not a medium problem`() {
        assertFalse(ProblemGenerator.hasBorrow(50, 30))
        assertFalse(
            ProblemGenerator.isWellFormed(
                Problem(Operation.SUBTRACT, listOf(50, 30), 20),
                Difficulty.MEDIUM,
            ),
        )
    }

    /** MEDIUM `a × b`, a,b ∈ 3..12, with the degenerate `× 10` struck out. */
    @Test
    fun `every medium multiplication is a times table without a degenerate factor`() {
        val products = sample(Difficulty.MEDIUM).filter { it.operation == Operation.MULTIPLY }
        assertTrue("no medium multiplications were generated", products.isNotEmpty())
        products.forEach {
            val (a, b) = it.operands
            assertTrue("$it: a out of 3..12", a in 3..12)
            assertTrue("$it: b out of 3..12", b in 3..12)
            assertTrue("$it: a is degenerate", ProblemGenerator.isAllowedFactor(a))
            assertTrue("$it: b is degenerate", ProblemGenerator.isAllowedFactor(b))
            assertEquals(a * b, it.answer)
        }
    }

    @Test
    fun `medium only ever produces addition, subtraction or multiplication`() {
        val operations = sample(Difficulty.MEDIUM).map { it.operation }.toSet()
        assertEquals(
            setOf(Operation.ADD, Operation.SUBTRACT, Operation.MULTIPLY),
            operations,
        )
    }

    // ------------------------------------------------------------------- hard

    /** HARD `ab × c` — a genuinely two-digit factor, never a multiple of ten. */
    @Test
    fun `hard multiplication uses a real two-digit factor`() {
        val products = sample(Difficulty.HARD).filter { it.operation == Operation.MULTIPLY }
        assertTrue("no hard multiplications were generated", products.isNotEmpty())
        products.forEach {
            val (ab, c) = it.operands
            assertTrue("$it: first factor is not two-digit", ab in 10..99)
            assertTrue("$it: $ab is a multiple of ten", ab % 10 != 0)
            assertTrue("$it: second factor out of 2..9", c in 2..9)
            assertEquals(ab * c, it.answer)
        }
    }

    /** HARD two-step `a × b + c` and `a × b - c`, both present and both positive. */
    @Test
    fun `hard two-step problems appear in both directions and stay positive`() {
        val twoStep = sample(Difficulty.HARD).filter {
            it.operation == Operation.MULTIPLY_ADD || it.operation == Operation.MULTIPLY_SUBTRACT
        }
        assertTrue("no hard two-step problems were generated", twoStep.isNotEmpty())
        assertTrue(twoStep.any { it.operation == Operation.MULTIPLY_ADD })
        assertTrue(twoStep.any { it.operation == Operation.MULTIPLY_SUBTRACT })
        twoStep.forEach {
            val (a, b, c) = it.operands
            assertTrue("$it: a is degenerate", ProblemGenerator.isAllowedFactor(a))
            assertTrue("$it: b is degenerate", ProblemGenerator.isAllowedFactor(b))
            assertTrue("$it: c must be at least 2", c >= 2)
            assertTrue("$it: result must be positive", it.answer > 0)
            val expected =
                if (it.operation == Operation.MULTIPLY_ADD) a * b + c else a * b - c
            assertEquals(expected, it.answer)
        }
    }

    /**
     * HARD clean division, built backwards from a known product: the dividend is always a multiple
     * of the divisor, so the answer is always an integer. There is no rounding anywhere.
     */
    @Test
    fun `hard division is always exact and never degenerate`() {
        val divisions = sample(Difficulty.HARD).filter { it.operation == Operation.DIVIDE }
        assertTrue("no hard divisions were generated", divisions.isNotEmpty())
        divisions.forEach {
            val (dividend, divisor) = it.operands
            assertEquals("$it: division must be exact", 0, dividend % divisor)
            assertEquals(dividend / divisor, it.answer)
            assertTrue("$it: divisor is degenerate", ProblemGenerator.isAllowedFactor(divisor))
            assertTrue("$it: quotient is degenerate", ProblemGenerator.isAllowedFactor(it.answer))
            assertTrue("$it: dividend should be at least two digits", dividend >= 10)
        }
    }

    @Test
    fun `hard only ever produces its four listed forms`() {
        val operations = sample(Difficulty.HARD).map { it.operation }.toSet()
        assertEquals(
            setOf(
                Operation.MULTIPLY,
                Operation.MULTIPLY_ADD,
                Operation.MULTIPLY_SUBTRACT,
                Operation.DIVIDE,
            ),
            operations,
        )
    }

    // -------------------------------------------------------------- universal

    /** "Answers are always integers and always positive", across every tier. */
    @Test
    fun `no tier ever produces a zero or negative answer`() {
        Difficulty.entries.forEach { difficulty ->
            sample(difficulty).forEach {
                assertTrue("$difficulty $it: answer must be positive", it.answer > 0)
                it.operands.forEach { operand ->
                    assertTrue("$difficulty $it: operands must be positive", operand > 0)
                }
            }
        }
    }

    /**
     * "Reject degenerate operands: no ×0, ×1, ×10, +0", across every tier.
     *
     * A division's factors are its **divisor and its quotient** — the two numbers the problem is
     * built from — not its dividend, which is their product. Asserting the rule against the
     * dividend was the test half of review M2-1.4: it demanded that `20 ÷ 4 = 5` never be
     * generated, when the generator was only failing to generate it because `isWellFormed` was
     * applying the factor rule to the wrong operand. This checks the same count of numbers per
     * problem, and now the right ones.
     */
    @Test
    fun `no tier ever produces a degenerate factor or a zero operand`() {
        Difficulty.entries.forEach { difficulty ->
            sample(difficulty).forEach { problem ->
                val factors = when (problem.operation) {
                    Operation.MULTIPLY -> problem.operands
                    Operation.DIVIDE -> listOf(problem.operands[1], problem.answer)
                    Operation.MULTIPLY_ADD, Operation.MULTIPLY_SUBTRACT ->
                        problem.operands.take(2)
                    Operation.ADD, Operation.SUBTRACT -> emptyList()
                }
                factors.forEach {
                    assertTrue(
                        "$difficulty ${problem.text}: $it is degenerate",
                        ProblemGenerator.isAllowedFactor(it),
                    )
                }
            }
        }
    }

    /**
     * Review M2-1.4, first direction: the validator really does hold the **quotient** to the
     * degenerate-factor rule, which is what its KDoc always claimed and what it never did. All
     * three of these used to pass `isWellFormed(.., HARD)` — the old line checked nothing but the
     * dividend's magnitude — and `96 ÷ 96 = 1` is not arithmetic.
     */
    @Test
    fun `the hard division validator rejects a degenerate quotient`() {
        listOf(
            Problem(Operation.DIVIDE, listOf(96, 96), 1),
            Problem(Operation.DIVIDE, listOf(12, 12), 1),
            Problem(Operation.DIVIDE, listOf(80, 8), 10),
            Problem(Operation.DIVIDE, listOf(110, 11), 10),
            Problem(Operation.DIVIDE, listOf(144, 144), 1),
        ).forEach {
            assertFalse(
                "${it.text} = ${it.answer} must not be a well-formed HARD division",
                ProblemGenerator.isWellFormed(it, Difficulty.HARD),
            )
        }
        // A degenerate *divisor* is still rejected too, and so is a sub-two-digit dividend.
        assertFalse(ProblemGenerator.isWellFormed(Problem(Operation.DIVIDE, listOf(5, 1), 5), Difficulty.HARD))
        assertFalse(ProblemGenerator.isWellFormed(Problem(Operation.DIVIDE, listOf(50, 10), 5), Difficulty.HARD))
        assertFalse(ProblemGenerator.isWellFormed(Problem(Operation.DIVIDE, listOf(9, 3), 3), Difficulty.HARD))
        // And the universal integrality check still stands.
        assertFalse(ProblemGenerator.isWellFormed(Problem(Operation.DIVIDE, listOf(13, 4), 3), Difficulty.HARD))
    }

    /**
     * Review M2-1.4, second direction: the validator must not throw away legitimate problems. The
     * old `isAllowedFactor(dividend)` clause rejected 8 of the 72 constructible divisor/quotient
     * pairs — every pair whose product is a multiple of ten — which is 11 % of division draws
     * silently discarded and re-drawn. The dividend is a product, not a factor.
     */
    @Test
    fun `the hard division validator accepts every constructible pair`() {
        // The three the finding names explicitly.
        listOf(
            Problem(Operation.DIVIDE, listOf(20, 4), 5),
            Problem(Operation.DIVIDE, listOf(30, 6), 5),
            Problem(Operation.DIVIDE, listOf(60, 12), 5),
        ).forEach {
            assertTrue(
                "${it.text} = ${it.answer} is a legitimate HARD division",
                ProblemGenerator.isWellFormed(it, Difficulty.HARD),
            )
        }

        // And the whole constructible space: divisor from 3..12 and quotient from 4..12, both with
        // multiples of ten struck out, exactly as the generator builds them.
        val divisors = (3..12).filter(ProblemGenerator::isAllowedFactor)
        val quotients = (4..12).filter(ProblemGenerator::isAllowedFactor)
        assertEquals(9, divisors.size)
        assertEquals(8, quotients.size)
        var accepted = 0
        divisors.forEach { divisor ->
            quotients.forEach { quotient ->
                val problem = Problem(Operation.DIVIDE, listOf(divisor * quotient, divisor), quotient)
                assertTrue(
                    "${problem.text} = $quotient was rejected",
                    ProblemGenerator.isWellFormed(problem, Difficulty.HARD),
                )
                accepted++
            }
        }
        assertEquals("every constructible pair must be well formed", 72, accepted)
    }

    /** A division is a HARD form only; the other two tiers must still refuse it. */
    @Test
    fun `a clean division is not well formed for the easier tiers`() {
        val division = Problem(Operation.DIVIDE, listOf(20, 4), 5)
        assertTrue(ProblemGenerator.isWellFormed(division, Difficulty.HARD))
        assertFalse(ProblemGenerator.isWellFormed(division, Difficulty.EASY))
        assertFalse(ProblemGenerator.isWellFormed(division, Difficulty.MEDIUM))
    }

    /** Every generated problem also satisfies the validator, at every tier. */
    @Test
    fun `every generated problem is well formed for its own tier`() {
        Difficulty.entries.forEach { difficulty ->
            sample(difficulty).forEach {
                assertTrue(
                    "$difficulty ${it.text} = ${it.answer} failed its own rules",
                    ProblemGenerator.isWellFormed(it, difficulty),
                )
            }
        }
    }

    /** A problem is never well formed for a tier it does not belong to. */
    @Test
    fun `a problem from one tier is not well formed for another`() {
        val easy = Problem(Operation.ADD, listOf(3, 4), 7)
        assertTrue(ProblemGenerator.isWellFormed(easy, Difficulty.EASY))
        assertFalse(ProblemGenerator.isWellFormed(easy, Difficulty.MEDIUM))
        assertFalse(ProblemGenerator.isWellFormed(easy, Difficulty.HARD))
    }

    // ----------------------------------------------------- the rules by name

    /** The degenerate-factor rule as a truth table: `× 0`, `× 1`, `× 10` and every `× 10n`. */
    @Test
    fun `degenerate factors are rejected and real ones accepted`() {
        listOf(0, 1, 10, 20, 30, 70, 90).forEach {
            assertFalse("$it should be rejected", ProblemGenerator.isAllowedFactor(it))
        }
        listOf(2, 3, 7, 9, 11, 12, 37, 99).forEach {
            assertTrue("$it should be accepted", ProblemGenerator.isAllowedFactor(it))
        }
    }

    /** Carry and borrow are decided by the digits, not by operand magnitude. */
    @Test
    fun `carry and borrow are decided by digits not by magnitude`() {
        assertTrue(ProblemGenerator.hasCarry(47, 28))
        assertTrue(ProblemGenerator.hasCarry(19, 11))
        assertFalse(ProblemGenerator.hasCarry(20, 30))
        assertFalse(ProblemGenerator.hasCarry(91, 12))

        assertTrue(ProblemGenerator.hasBorrow(52, 38))
        assertTrue(ProblemGenerator.hasBorrow(90, 11))
        assertFalse(ProblemGenerator.hasBorrow(50, 30))
        assertFalse(ProblemGenerator.hasBorrow(58, 32))
    }

    /** The validator is not a tautology: hand-built bad problems are rejected one rule at a time. */
    @Test
    fun `well formedness rejects hand built bad problems`() {
        // A negative answer.
        assertFalse(
            ProblemGenerator.isWellFormed(
                Problem(Operation.SUBTRACT, listOf(3, 9), -6),
                Difficulty.EASY,
            ),
        )
        // Identical operands at EASY.
        assertFalse(
            ProblemGenerator.isWellFormed(
                Problem(Operation.ADD, listOf(5, 5), 10),
                Difficulty.EASY,
            ),
        )
        // `+ 0`.
        assertFalse(
            ProblemGenerator.isWellFormed(
                Problem(Operation.ADD, listOf(40, 0), 40),
                Difficulty.MEDIUM,
            ),
        )
        // `× 1` and `× 10`.
        assertFalse(
            ProblemGenerator.isWellFormed(
                Problem(Operation.MULTIPLY, listOf(7, 1), 7),
                Difficulty.MEDIUM,
            ),
        )
        assertFalse(
            ProblemGenerator.isWellFormed(
                Problem(Operation.MULTIPLY, listOf(7, 10), 70),
                Difficulty.MEDIUM,
            ),
        )
        // A division that does not divide evenly.
        assertFalse(
            ProblemGenerator.isWellFormed(
                Problem(Operation.DIVIDE, listOf(50, 4), 12),
                Difficulty.HARD,
            ),
        )
        // An answer that simply is not the arithmetic.
        assertFalse(
            ProblemGenerator.isWellFormed(
                Problem(Operation.MULTIPLY, listOf(37, 8), 295),
                Difficulty.HARD,
            ),
        )
    }

    // ------------------------------------------------------- seeds and repeats

    /** Determinism: the whole test suite and the saved-state restore both depend on it. */
    @Test
    fun `the same seed produces the same problem`() {
        Difficulty.entries.forEach { difficulty ->
            val first = ProblemGenerator.generate(difficulty, Random(7L))
            val second = ProblemGenerator.generate(difficulty, Random(7L))
            assertEquals(first, second)
        }
    }

    /** The no-repeat mechanism: an excluded text is never handed back while alternatives exist. */
    @Test
    fun `generate never returns a problem that is already excluded`() {
        Difficulty.entries.forEach { difficulty ->
            val random = Random(99L)
            val seen = mutableSetOf<String>()
            repeat(40) {
                val problem = ProblemGenerator.generate(difficulty, random, seen)
                assertFalse(
                    "$difficulty repeated ${problem.text} after ${seen.size} draws",
                    problem.text in seen,
                )
                seen += problem.text
            }
        }
    }

    /**
     * The degradation path: when the exclusion set has swallowed a tier's whole problem space the
     * generator still returns a problem rather than looping or throwing. A repeated question beats
     * an alarm with no question, because the latter cannot be dismissed at all.
     */
    @Test
    fun `generate still returns a problem when everything is excluded`() {
        Difficulty.entries.forEach { difficulty ->
            val random = Random(5L)
            val everything = List(4_000) {
                ProblemGenerator.generate(difficulty, random).text
            }.toSet()
            val problem = ProblemGenerator.generate(difficulty, random, everything)
            assertNotNull(problem)
            assertTrue(problem.answer > 0)
            assertTrue(ProblemGenerator.isWellFormed(problem, difficulty))
        }
    }

    /** No tier can produce an empty or malformed display string. */
    @Test
    fun `every problem renders a non-empty question`() {
        Difficulty.entries.forEach { difficulty ->
            sample(difficulty, count = 200).forEach {
                assertTrue(it.text.isNotBlank())
                assertFalse(it.text.contains("null"))
            }
        }
    }
}
