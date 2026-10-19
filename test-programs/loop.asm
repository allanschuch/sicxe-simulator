. Soma 2 cinco vezes. RESULT deve conter 10 (00000A).
DEMO    START 0
FIRST   CLEAR X
        LDA #0
        LDT #5
LOOP    ADD #2
        TIXR T
        JLT LOOP
        STA RESULT
STOP    J STOP
RESULT  RESW 1
        END FIRST
