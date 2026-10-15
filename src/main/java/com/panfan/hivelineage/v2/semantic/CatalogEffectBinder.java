package com.panfan.hivelineage.v2.semantic;

/** A catalog semantic family consumes the same object/precondition resolution contract. */
interface CatalogEffectBinder {
    EffectBindingResult bind(CatalogEffectRequest request);
}
