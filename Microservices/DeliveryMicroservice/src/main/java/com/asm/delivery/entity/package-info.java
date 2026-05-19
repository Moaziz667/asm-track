@FilterDef(
    name = "companyFilter",
    parameters = @ParamDef(name = "companyId", type = UUID.class)
)
package com.asm.delivery.entity;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import java.util.UUID;
