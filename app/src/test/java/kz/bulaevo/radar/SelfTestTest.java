package kz.bulaevo.radar;

import static org.junit.Assert.assertEquals;

import kz.bulaevo.radar.dsp.SelfTest;

import org.junit.Test;

/** Прогоняет симуляцию комнаты через всю цепочку обработки сонара. */
public class SelfTestTest {
    @Test
    public void sonarMathWorksOnSimulatedRoom() {
        SelfTest.Report r = SelfTest.runAll();
        assertEquals(r.text(), 0, r.failures);
    }
}
