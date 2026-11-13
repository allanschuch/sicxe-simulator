. Macros: chamada aninhada (INIT chama CLRREG) e definição aninhada (MKADD define ADDK).
. A = 3 + 10 + 4 = 17 depois de cinco instruções; o resultado fica em RESULT.
MACROS  START 0





. FIRST   INIT 3
FIRST EQU *
.   CLRREG X
        CLEAR X
        LDA #3
. MKADD 10
. ADDK 4
        ADD #10
        ADD #4
. SAVE A,RESULT
        STA RESULT
STOP    J STOP
RESULT  RESW 1
        END FIRST
