# Diagnostic: bouton Planifier grisé avec intervalle personnalisé

## Symptôme
Lorsqu'un utilisateur bascule sur « Intervalle personnalisé » dans la feuille de rappel récurrent et saisit une valeur valide (par exemple 10 minutes), le bouton **Planifier** du mode temps reste grisé et ne peut pas être pressé.

## Analyse
Le bouton `btnPlanTime` est activé/désactivé via la méthode `updatePlanTimeButtonState()` dans `BottomSheetReminderPicker`. Cette méthode ne regarde qu'un seul critère : l'attribut `selectedDateTimeMillis` doit être non nul.

```kotlin
private fun updatePlanTimeButtonState() {
    val button = planTimeButton ?: return
    button.isEnabled = selectedDateTimeMillis != null
}
```

La valeur `selectedDateTimeMillis` n'est renseignée que par `setSelectedDateTime(...)`, qui est appelé lorsque l'utilisateur choisit un horaire explicite (« Dans 10 minutes », « Heure personnalisée… », etc.). Le passage en « Intervalle personnalisé » ou la saisie d'une valeur dans `editRepeatCustom` ne met jamais à jour cette propriété ; la séquence `updateRepeatEveryMinutes()` ↦ `computeCustomRepeatMinutes()` ne touche que `repeatEveryMinutes` et `repeatSelectionValid`.

Conséquence : si aucun horaire n'a été choisi au préalable, `selectedDateTimeMillis` reste `null` et le bouton demeure désactivé, même si l'intervalle personnalisé est valide.

## Conclusion
Le bouton est grisé parce que l'écran exige toujours la sélection d'un premier horaire (`selectedDateTimeMillis`). L'intervalle personnalisé ne définit pas cet horaire, donc le prérequis reste non satisfait tant qu'aucun bouton d'heure (« Dans 10 minutes », « Heure personnalisée… », etc.) n'a été pressé.
