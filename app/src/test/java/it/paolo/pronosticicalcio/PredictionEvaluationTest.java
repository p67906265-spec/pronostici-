package it.paolo.pronosticicalcio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PredictionEvaluationTest {

    @Test
    public void riconosceGliEsiti1x2() {
        assertEquals("1", PredictionEvaluation.actual1x2(2, 0));
        assertEquals("X", PredictionEvaluation.actual1x2(1, 1));
        assertEquals("2", PredictionEvaluation.actual1x2(0, 3));
    }

    @Test
    public void goalRichiedeCheSegninoEntrambe() {
        assertEquals("GOAL", PredictionEvaluation.actualGoal(2, 1));
        assertEquals("NO GOAL", PredictionEvaluation.actualGoal(3, 0));
        assertEquals("NO GOAL", PredictionEvaluation.actualGoal(0, 0));
    }

    @Test
    public void over25RichiedeAlmenoTreGol() {
        assertEquals("UNDER 2,5", PredictionEvaluation.actualOver25(1, 1));
        assertEquals("OVER 2,5", PredictionEvaluation.actualOver25(2, 1));
    }

    @Test
    public void over15RichiedeAlmenoDueGol() {
        assertEquals("UNDER 1,5", PredictionEvaluation.actualOver15(1, 0));
        assertEquals("OVER 1,5", PredictionEvaluation.actualOver15(1, 1));
    }
}
