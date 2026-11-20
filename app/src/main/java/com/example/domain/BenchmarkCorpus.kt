package com.example.domain

data class BenchmarkItem(
    val id: String,
    val categoryAr: String,
    val categoryFr: String,
    val frenchText: String
)

data class StarterBookSeed(
    val id: String,
    val title: String,
    val author: String,
    val level: String,
    val sourceLanguage: String = "fr",
    val chapters: List<Pair<String, String>>
)

object BenchmarkCorpus {

    val items: List<BenchmarkItem> = listOf(
        BenchmarkItem(
            id = "nasals",
            categoryAr = "الحروف الأنفية (Voyelles nasales)",
            categoryFr = "Voyelles nasales",
            frenchText = "Le bon pain blanc du matin est très important pour notre santé."
        ),
        BenchmarkItem(
            id = "silent_letters",
            categoryAr = "الحروف الصامتة (Lettres muettes)",
            categoryFr = "Lettres muettes",
            frenchText = "Ce petit garçon et ses sœurs parlent beaucoup depuis longtemps."
        ),
        BenchmarkItem(
            id = "liaisons",
            categoryAr = "الروابط الصوتية (Liaisons)",
            categoryFr = "Liaisons obligatoires",
            frenchText = "Les amis que vous avez invités ont un enfant très intéressant."
        ),
        BenchmarkItem(
            id = "minimal_pairs",
            categoryAr = "الأصوات المتقاربة (Paires minimales)",
            categoryFr = "Oppositions /y/-/u/ et /s/-/z/",
            frenchText = "Le chat saute dessus puis passe dessous ; ce poisson frais n'est pas un poison."
        ),
        BenchmarkItem(
            id = "numbers_currency",
            categoryAr = "الأرقام والعملات (Nombres et devises)",
            categoryFr = "Nombres et devises",
            frenchText = "En 1998 puis en 2026, le billet coûtait 3,75 € et le vélo valait 1 250 €."
        ),
        BenchmarkItem(
            id = "questions_punctuation",
            categoryAr = "علامات الترقيم والأسئلة (Questions)",
            categoryFr = "Intonation interrogative",
            frenchText = "Pourquoi êtes-vous ici ? Qu'est-ce que vous faites aujourd'hui ? Il fait beau !"
        ),
        BenchmarkItem(
            id = "long_passage",
            categoryAr = "نص طويل (350+ كلمة للاستقرار والذاكرة)",
            categoryFr = "Passage long (350+ mots)",
            frenchText = """
                Chaque matin, lorsque le soleil se lève doucement derrière les toits gris de Paris, la rue Saint-Honoré s'éveille dans une atmosphère paisible et familière. M. Moreau, le boulanger du quartier, ouvre les volets en bois de sa boutique dès six heures et demie. L'odeur chaude du pain croustillant, des croissants au beurre et des brioches dorées envahit aussitôt le trottoir humide.
                Les premiers clients arrivent sans hâte. Mme. Lefèvre salue poliment le commerçant, choisit deux baguettes bien cuites et glisse quatre pièces sur le comptoir en marbre. Plus loin, un étudiant consulte l'heure sur sa montre : il est déjà huit heures moins dix, et son train pour Lyon part dans vingt-cinq minutes exactement.
                Dans le jardin public voisin, les enfants courent autour de la fontaine tandis que les oiseaux chantent dans les grands marronniers. Pourquoi cette scène simple reste-t-elle si agréable au fil des années ? Peut-être parce qu'elle rassemble les petits gestes du quotidien, la patience des saisons et la chaleur des conversations simples entre voisins.
                À midi, les terrasses des cafés se remplissent progressivement. Deux amis discutent d'un roman qu'ils ont lu pendant l'hiver 2026, comparent leurs passages préférés et notent quelques expressions difficiles dans un carnet. L'un d'eux explique qu'écouter une langue étrangère tous les jours, même pendant quinze minutes, transforme peu à peu la compréhension orale. Au début, certains mots semblent rapides et liés entre eux, comme « les amis » ou « vous avez ». Mais avec de la régularité, l'oreille distingue chaque syllabe, reconnaît les liaisons naturelles et retrouve le sens de la phrase entière sans effort excessif.
                Quand le soir descend sur la Seine, les lampadaires s'allument un à un le long des quais. Les promeneurs ralentissent le pas, observent les reflets dorés sur l'eau calme et savourent le silence retrouvé. La ville continue de respirer, entre mémoire ancienne et promesse du lendemain, offrant à chaque lecteur attentif une nouvelle page à découvrir et à écouter.
            """.trimIndent()
        )
    )

    val starterBooks: List<StarterBookSeed> = listOf(
        StarterBookSeed(
            id = "starter_matin_paris",
            title = "Un Matin à Paris",
            author = "Édition Écoute (A2–B1)",
            level = "A2–B1",
            chapters = listOf(
                "Chapitre 1 : La Boulangerie" to """
                    Il est sept heures du matin. Le ciel au-dessus de Paris est encore rose et frais.
                    M. Bernard prépare le café chaud et sort les baguettes dorées du four.
                    « Bonjour Madame ! Vous avez passé une bonne nuit ? » demande-t-il avec un grand sourire.
                    « Oui, merci beaucoup ! Aujourd'hui, il fait beau et j'ai invité les amis à déjeuner. »
                    Elle achète trois pains au chocolat pour 3,75 € et trois croissants bien chauds.
                """.trimIndent(),
                "Chapitre 2 : La Promenade sur la Seine" to """
                    À dix heures, Nadia marche lentement près du fleuve avec son petit carnet bleu.
                    Elle écoute attentivement les conversations des passants et note les expressions utiles.
                    Pourquoi êtes-vous ici si tôt ? lui demande un vieux libraire qui installe ses caisses vertes.
                    « J'apprends le français en lisant et en écoutant chaque phrase avec patience », répond Nadia.
                    Le libraire hoche la tête : en 1998 déjà, il conseillait à ses élèves de lire à voix haute pendant quinze minutes par jour.
                """.trimIndent()
            )
        ),
        StarterBookSeed(
            id = "starter_corbeau_renard",
            title = "Fables Choisies & Dialogues",
            author = "Jean de La Fontaine (Domaine Public)",
            level = "B1–B2",
            chapters = listOf(
                "Le Corbeau et le Renard" to """
                    Maître Corbeau, sur un arbre perché, tenait en son bec un fromage.
                    Maître Renard, par l'odeur alléché, lui tint à peu près ce langage :
                    « Hé ! bonjour, Monsieur du Corbeau. Que vous êtes joli ! Que vous me semblez beau ! »
                    Sans mentir, si votre ramage se rapporte à votre plumage, vous êtes le phénix des hôtes de ces bois.
                    À ces mots, le Corbeau ne se sent pas de joie ; et pour montrer sa belle voix, il ouvre un large bec, laisse tomber sa proie.
                    Le Renard s'en saisit, et dit : « Mon bon Monsieur, apprenez que tout flatteur vit aux dépens de celui qui l'écoute. »
                """.trimIndent(),
                "Le Petit Voyageur" to """
                    En 2026, Karim prend le train rapide pour Marseille avec un billet à 45 € seulement.
                    Dans le wagon, un enfant regarde les collines défiler par la fenêtre.
                    Qu'est-ce que vous faites aujourd'hui en arrivant au port ?
                    Nous visitons d'abord le vieux quartier, puis nous prenons un poisson grillé près de la mer.
                """.trimIndent()
            )
        ),
        StarterBookSeed(
            id = "starter_english_reading",
            title = "The Joy of Active Reading",
            author = "Écoute English Library (B1)",
            level = "EN • B1",
            sourceLanguage = "en",
            chapters = listOf(
                "Chapter 1: Morning Focus" to """
                    Reading in a foreign language becomes easier when you understand ideas in their natural context.
                    Instead of memorizing isolated word lists, notice how phrases connect across a full paragraph.

                    Take a few minutes every morning to read slowly and verify the Arabic translation only when needed.
                    With steady daily practice, complex English sentences soon feel clear and familiar.
                """.trimIndent()
            )
        )
    )
}
