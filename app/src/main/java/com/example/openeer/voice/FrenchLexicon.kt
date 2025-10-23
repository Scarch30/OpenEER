package com.example.openeer.voice

import java.text.Normalizer
import java.util.Locale

object FrenchLexicon {

    private val DIACRITICS_REGEX = "\\p{Mn}+".toRegex()
    private val TRIM_CHARS = charArrayOf(
        ' ', ',', ';', '.', '?', '!', ':', '\"', '\'', '’', '«', '»', '(', ')', '[', ']', '{', '}', '/', '\\', '-', '_'
    )

    private val COMMON_ADJECTIVES: Set<String> = buildCommonAdjectives()
    private val DETERMINERS: Set<String> = normalizedSetOf(
        "le",
        "la",
        "les",
        "l",
        "un",
        "une",
        "des",
        "du",
        "de",
        "d",
        "quelques",
        "plusieurs",
        "ce",
        "cet",
        "cette",
        "ces",
        "mon",
        "ma",
        "mes",
        "ton",
        "ta",
        "tes",
        "son",
        "sa",
        "ses",
        "notre",
        "nos",
        "votre",
        "vos",
        "leur",
        "leurs",
        "aucun",
        "aucune",
        "chaque",
        "tout",
        "toute",
        "tous",
        "toutes",
        "quel",
        "quelle",
        "quelque"
    )
    private val PREPOSITIONS: Set<String> = normalizedSetOf(
        "avec",
        "chez",
        "sans",
        "sous",
        "sur",
        "pour",
        "par",
        "dans",
        "vers",
        "entre",
        "contre",
        "apres",
        "avant",
        "pendant",
        "depuis",
        "selon",
        "malgre",
        "jusque",
        "pres",
        "loin",
        "parmi"
    )
    private val CONJUNCTIONS: Set<String> = normalizedSetOf(
        "et",
        "ou",
        "mais",
        "donc",
        "or",
        "ni",
        "car"
    )
    private val OTHER_STOPWORDS: Set<String> = normalizedSetOf(
        "plus",
        "moins",
        "tres",
        "bien",
        "assez",
        "beaucoup",
        "encore",
        "aussi",
        "comme",
        "ainsi",
        "presque",
        "juste",
        "seulement"
    )
    private val KNOWN_NOUNS: Set<String> = normalizedSetOf(
        "pomme",
        "pommes",
        "tomate",
        "tomates",
        "carotte",
        "carottes",
        "oignon",
        "oignons",
        "poireau",
        "poireaux",
        "courgette",
        "courgettes",
        "banane",
        "bananes",
        "poivron",
        "poivrons",
        "concombre",
        "concombres",
        "patate",
        "patates",
        "pates",
        "pate",
        "riz",
        "yaourt",
        "yaourts",
        "fromage",
        "lait",
        "beurre",
        "oeuf",
        "oeufs",
        "pain",
        "baguette",
        "biscuit",
        "biscuits",
        "gateau",
        "gateaux",
        "farine",
        "sucre",
        "sel",
        "poivre",
        "huile",
        "vinaigre",
        "eau",
        "jus",
        "cafe",
        "the",
        "soupe",
        "legume",
        "legumes",
        "fruit",
        "fruits",
        "viande",
        "steak",
        "poulet",
        "dinde",
        "jambon",
        "saucisse",
        "poisson",
        "crevette",
        "crevettes",
        "moule",
        "moules",
        "salade",
        "salades",
        "avocat",
        "avocats",
        "champignon",
        "champignons",
        "lardon",
        "lardons",
        "sauce",
        "sauces",
        "cereale",
        "cereales",
        "barre",
        "barres",
        "ail",
        "echalote",
        "echalotes",
        "pommeau",
        "compote",
        "compotes",
        "lessive",
        "savon",
        "dentifrice",
        "shampoing",
        "papier",
        "essuie",
        "torchon",
        "torchons",
        "eponge",
        "eponges"
    )

    fun normalize(word: String): String {
        if (word.isEmpty()) return ""
        val replaced = word.replace('’', '\'')
        val trimmed = replaced.trim(*TRIM_CHARS)
        if (trimmed.isEmpty()) return ""
        val lowered = trimmed.lowercase(Locale.FRENCH)
        val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        return DIACRITICS_REGEX.replace(decomposed, "")
    }

    fun isDeterminer(normalizedWord: String): Boolean {
        if (normalizedWord.isEmpty()) return false
        return normalizedWord in DETERMINERS
    }

    fun isLikelyAdj(normalizedWord: String): Boolean {
        if (normalizedWord.isEmpty()) return false
        return normalizedWord in COMMON_ADJECTIVES
    }

    fun isLikelyNoun(normalizedWord: String): Boolean {
        if (normalizedWord.isEmpty()) return false
        if (normalizedWord in DETERMINERS) return false
        if (normalizedWord in PREPOSITIONS) return false
        if (normalizedWord in CONJUNCTIONS) return false
        if (normalizedWord in OTHER_STOPWORDS) return false
        if (normalizedWord in COMMON_ADJECTIVES) return false
        if (normalizedWord in KNOWN_NOUNS) return true
        if (normalizedWord.all { it.isDigit() }) return false
        val letterCount = normalizedWord.count { it.isLetter() }
        if (letterCount >= 3) return true
        val last = normalizedWord.lastOrNull() ?: return false
        return last == 's' || last == 'x' || last == 'e'
    }

    private fun buildCommonAdjectives(): Set<String> {
        val result = mutableSetOf<String>()
        SIMPLE_ADJECTIVE_BASES.forEach { base ->
            result.addAll(expandRegularAdjective(base))
        }
        IRREGULAR_ADJECTIVES.forEach { forms -> result.addAll(forms) }
        COLOR_ADJECTIVES.forEach { forms -> result.addAll(forms) }
        return result.map { normalize(it) }.toSet()
    }

    private fun expandRegularAdjective(base: String): Set<String> {
        val normalizedBase = base.lowercase(Locale.FRENCH)
        val forms = mutableSetOf<String>()
        forms.add(normalizedBase)
        val feminine = when {
            normalizedBase.endsWith("f") -> normalizedBase.dropLast(1) + "ve"
            normalizedBase.endsWith("x") -> normalizedBase.dropLast(1) + "se"
            normalizedBase.endsWith("g") -> normalizedBase + "ue"
            normalizedBase.endsWith("c") -> normalizedBase + "he"
            normalizedBase.endsWith("el") -> normalizedBase + "le"
            normalizedBase.endsWith("eil") -> normalizedBase + "le"
            normalizedBase.endsWith("er") -> normalizedBase.dropLast(1) + "ere"
            normalizedBase.endsWith("et") -> normalizedBase + "te"
            normalizedBase.endsWith("ot") -> normalizedBase + "te"
            normalizedBase.endsWith("ien") -> normalizedBase + "ne"
            normalizedBase.endsWith("s") -> normalizedBase
            normalizedBase.endsWith("e") -> normalizedBase
            else -> normalizedBase + "e"
        }
        forms.add(feminine)
        val masculinePlural = when {
            normalizedBase.endsWith("al") -> normalizedBase.dropLast(2) + "aux"
            normalizedBase.endsWith("au") -> normalizedBase + "x"
            normalizedBase.endsWith("eu") -> normalizedBase + "x"
            normalizedBase.endsWith("s") || normalizedBase.endsWith("x") -> normalizedBase
            else -> normalizedBase + "s"
        }
        forms.add(masculinePlural)
        val femininePlural = when {
            feminine.endsWith("s") || feminine.endsWith("x") -> feminine
            else -> feminine + "s"
        }
        forms.add(femininePlural)
        return forms
    }

    private fun normalizedSetOf(vararg entries: String): Set<String> {
        return entries.map { normalize(it) }.toSet()
    }

    private val SIMPLE_ADJECTIVE_BASES = listOf(
        "petit",
        "grand",
        "gros",
        "mince",
        "rapide",
        "lent",
        "joli",
        "mignon",
        "doux",
        "amer",
        "sucre",
        "sale",
        "epice",
        "piquant",
        "croquant",
        "croustillant",
        "moelleux",
        "tendre",
        "mur",
        "humide",
        "ancien",
        "neuf",
        "plein",
        "vide",
        "court",
        "epais",
        "fin",
        "rond",
        "carre",
        "triangulaire",
        "brillant",
        "mat",
        "lourd",
        "leger",
        "solide",
        "fragile",
        "puissant",
        "faible",
        "chaud",
        "froid",
        "tiede",
        "sombre",
        "clair",
        "aigre",
        "gras",
        "maigre",
        "savoureux",
        "delicieux",
        "exquis",
        "fameux",
        "simple",
        "double",
        "triple",
        "extra",
        "ordinaire",
        "special",
        "intense",
        "calme",
        "bruyant",
        "propre",
        "poussiereux",
        "gluant",
        "collant",
        "odorant",
        "parfume",
        "aromatique",
        "acide",
        "lisse",
        "rugueux",
        "souple",
        "rigide",
        "spacieux",
        "etroit",
        "pointu",
        "tranchant",
        "lumineux",
        "chanceux",
        "nerveux",
        "orageux",
        "pluvieux",
        "venteux",
        "nuageux",
        "agreable",
        "adorable",
        "utile",
        "pratique",
        "classique",
        "moderne",
        "rustique",
        "savant",
        "creatif",
        "actif",
        "sportif",
        "massif",
        "neigeux",
        "petillant",
        "mousseux",
        "ferme",
        "juteux",
        "floral",
        "herbeux",
        "champetre",
        "montagneux",
        "citadin",
        "urbain",
        "rural",
        "boise",
        "anime",
        "raffine",
        "sophistique",
        "precis",
        "precieux",
        "rare",
        "commun",
        "principal",
        "secondaire",
        "important",
        "intelligent",
        "gentil",
        "heureux",
        "discret",
        "soigne",
        "colore",
        "suave",
        "onctueux",
        "cru",
        "cuit",
        "marine",
        "grille",
        "fume",
        "rape",
        "vaporeux",
        "rafraichi"
    )

    private val IRREGULAR_ADJECTIVES = listOf(
        listOf("beau", "belle", "beaux", "belles"),
        listOf("nouveau", "nouvelle", "nouveaux", "nouvelles"),
        listOf("vieux", "vieille", "vieux", "vieilles"),
        listOf("fou", "folle", "fous", "folles"),
        listOf("mou", "molle", "mous", "molles"),
        listOf("long", "longue", "longs", "longues"),
        listOf("blanc", "blanche", "blancs", "blanches"),
        listOf("public", "publique", "publics", "publiques"),
        listOf("sec", "seche", "secs", "seches"),
        listOf("frais", "fraiche", "frais", "fraiches"),
        listOf("doux", "douce", "doux", "douces"),
        listOf("roux", "rousse", "roux", "rousses"),
        listOf("flou", "floue", "flous", "floues"),
        listOf("malin", "maligne", "malins", "malignes"),
        listOf("gentil", "gentille", "gentils", "gentilles"),
        listOf("favori", "favorite", "favoris", "favorites"),
        listOf("raide", "raide", "raides", "raides"),
        listOf("rape", "rapee", "rapes", "rapees")
    )

    private val COLOR_ADJECTIVES = listOf(
        listOf("bleu", "bleue", "bleus", "bleues"),
        listOf("vert", "verte", "verts", "vertes"),
        listOf("rouge", "rouges"),
        listOf("jaune", "jaunes"),
        listOf("orange", "oranges"),
        listOf("noir", "noire", "noirs", "noires"),
        listOf("blanc", "blanche", "blancs", "blanches"),
        listOf("gris", "grise", "gris", "grises"),
        listOf("rose", "roses"),
        listOf("violet", "violette", "violets", "violettes"),
        listOf("marron", "marron"),
        listOf("beige", "beiges"),
        listOf("turquoise", "turquoises"),
        listOf("magenta", "magentas"),
        listOf("cyan", "cyans"),
        listOf("ocre", "ocres"),
        listOf("indigo", "indigos"),
        listOf("argent", "argentee", "argents", "argentees"),
        listOf("dore", "doree", "dores", "dorees"),
        listOf("brun", "brune", "bruns", "brunes"),
        listOf("auburn", "auburn"),
        listOf("olive", "olives")
    )
}

