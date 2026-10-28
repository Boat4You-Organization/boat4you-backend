package hr.workspace.boat4you.domains.chat.service

import java.util.Locale

/**
 * The fixed texts the server itself says in the chat (not the model): the apology after a model failure, the holding
 * message when the team has not answered a handoff, and the line while the AI is paused (spec 2.2, 2.6, 6.7).
 *
 * Nine languages, looked up by the first two letters of the session locale with English as the fallback. The texts
 * promise "email or phone" where a person may answer later, and never say that the team replies in this chat only.
 * None of them contains a tool name or a prompt fragment, so [ChatSecurityGuard.checkOutput] lets every one through.
 */
object ChatTexts {
    val LOCALES: List<String> = listOf("en", "hr", "fr", "de", "pt", "it", "es", "pl", "nl")

    fun failureApology(locale: String?): String = pick(FAILURE, locale)

    /** The one line the server posts when a handoff has waited too long; [hasContact] = the visitor already left an email or phone. */
    fun handoffHolding(
        locale: String?,
        hasContact: Boolean,
    ): String = pick(if (hasContact) HOLDING_HAS_CONTACT else HOLDING_ASK, locale)

    /** What a visitor reads while the AI is paused (circuit breaker open): the form offer instead of an answer. */
    fun aiPaused(locale: String?): String = pick(AI_PAUSED, locale)

    private fun pick(
        texts: Map<String, String>,
        locale: String?,
    ): String = texts[locale?.take(2)?.lowercase(Locale.ROOT)] ?: texts.getValue("en")

    private val FAILURE =
        mapOf(
            "en" to "Sorry — I hit a technical hiccup just now. Please try again in a moment, or ask for our team and a person will reply here.",
            "hr" to "Oprostite — upravo sam naišla na tehnički problem. Pokušajte ponovno za trenutak ili zatražite naš tim i osoba će vam odgovoriti ovdje.",
            "fr" to "Désolée — je viens de rencontrer un petit problème technique. Réessayez dans un instant, ou demandez notre équipe et une personne vous répondra ici.",
            "de" to "Entschuldigung — gerade ist ein technisches Problem aufgetreten. Bitte versuchen Sie es gleich noch einmal, oder fragen Sie nach unserem Team, dann antwortet Ihnen hier eine Person.",
            "pt" to "Desculpe — acabei de ter um problema técnico. Tente novamente dentro de momentos, ou peça a nossa equipa e uma pessoa responderá aqui.",
            "it" to "Mi scusi — ho appena avuto un problema tecnico. Riprovi tra un momento, oppure chieda del nostro team e una persona le risponderà qui.",
            "es" to "Lo siento — acabo de tener un problema técnico. Inténtelo de nuevo en un momento, o pida hablar con nuestro equipo y una persona le responderá aquí.",
            "pl" to "Przepraszam — właśnie wystąpił problem techniczny. Spróbuj ponownie za chwilę albo poproś o nasz zespół, a odpowie Ci tutaj człowiek.",
            "nl" to "Sorry — er ging net iets technisch mis. Probeer het zo opnieuw, of vraag naar ons team, dan antwoordt een medewerker u hier.",
        )

    private val HOLDING_ASK =
        mapOf(
            "en" to "Thank you for your patience. Our team could not reply in the chat yet, so a person will contact you by email or phone as soon as possible. Please leave your email address or phone number here. In the meantime I am happy to keep answering your questions.",
            "hr" to "Hvala na strpljenju. Naš tim vam još nije stigao odgovoriti u chatu, pa će vas osoba iz tima kontaktirati emailom ili telefonom što je prije moguće. Molim vas, ostavite ovdje svoju email adresu ili broj telefona. Do tada vam rado i dalje odgovaram na pitanja.",
            "fr" to "Merci de votre patience. Notre équipe n'a pas encore pu répondre dans le chat, une personne vous contactera donc par e-mail ou par téléphone dès que possible. Merci de laisser ici votre adresse e-mail ou votre numéro de téléphone. En attendant, je continue volontiers à répondre à vos questions.",
            "de" to "Vielen Dank für Ihre Geduld. Unser Team konnte im Chat noch nicht antworten, deshalb meldet sich so bald wie möglich eine Person per E-Mail oder Telefon bei Ihnen. Bitte hinterlassen Sie hier Ihre E-Mail-Adresse oder Telefonnummer. Bis dahin beantworte ich gerne weiter Ihre Fragen.",
            "pt" to "Obrigada pela sua paciência. A nossa equipa ainda não conseguiu responder no chat, por isso uma pessoa entrará em contacto consigo por e-mail ou telefone o mais rapidamente possível. Deixe aqui o seu e-mail ou número de telefone, por favor. Entretanto, continuo a responder com todo o gosto às suas perguntas.",
            "it" to "Grazie per la pazienza. Il nostro team non ha ancora potuto rispondere in chat, quindi una persona la contatterà via e-mail o telefono il prima possibile. Lasci qui il suo indirizzo e-mail o numero di telefono, per favore. Nel frattempo continuo volentieri a rispondere alle sue domande.",
            "es" to "Gracias por su paciencia. Nuestro equipo aún no ha podido responder en el chat, así que una persona se pondrá en contacto con usted por correo electrónico o por teléfono lo antes posible. Deje aquí su correo electrónico o su número de teléfono, por favor. Mientras tanto, sigo respondiendo con gusto a sus preguntas.",
            "pl" to "Dziękuję za cierpliwość. Nasz zespół nie zdążył jeszcze odpowiedzieć na czacie, dlatego wkrótce skontaktuje się z Tobą osoba z zespołu e-mailem lub telefonicznie. Zostaw tutaj swój adres e-mail lub numer telefonu. W międzyczasie chętnie dalej odpowiadam na Twoje pytania.",
            "nl" to "Bedankt voor uw geduld. Ons team kon nog niet in de chat antwoorden, daarom neemt zo snel mogelijk een medewerker per e-mail of telefoon contact met u op. Laat hier uw e-mailadres of telefoonnummer achter. Intussen beantwoord ik graag uw vragen.",
        )

    private val HOLDING_HAS_CONTACT =
        mapOf(
            "en" to "Thank you for your patience. Our team could not reply in the chat yet, so a person will contact you by email or phone as soon as possible, using the contact details you left. In the meantime I am happy to keep answering your questions.",
            "hr" to "Hvala na strpljenju. Naš tim vam još nije stigao odgovoriti u chatu, pa će vas osoba iz tima kontaktirati emailom ili telefonom što je prije moguće, na kontakt koji ste ostavili. Do tada vam rado i dalje odgovaram na pitanja.",
            "fr" to "Merci de votre patience. Notre équipe n'a pas encore pu répondre dans le chat, une personne vous contactera donc par e-mail ou par téléphone dès que possible, aux coordonnées que vous avez laissées. En attendant, je continue volontiers à répondre à vos questions.",
            "de" to "Vielen Dank für Ihre Geduld. Unser Team konnte im Chat noch nicht antworten, deshalb meldet sich so bald wie möglich eine Person per E-Mail oder Telefon über die Kontaktdaten, die Sie hinterlassen haben. Bis dahin beantworte ich gerne weiter Ihre Fragen.",
            "pt" to "Obrigada pela sua paciência. A nossa equipa ainda não conseguiu responder no chat, por isso uma pessoa entrará em contacto consigo por e-mail ou telefone o mais rapidamente possível, através dos contactos que deixou. Entretanto, continuo a responder com todo o gosto às suas perguntas.",
            "it" to "Grazie per la pazienza. Il nostro team non ha ancora potuto rispondere in chat, quindi una persona la contatterà via e-mail o telefono il prima possibile, ai recapiti che ha lasciato. Nel frattempo continuo volentieri a rispondere alle sue domande.",
            "es" to "Gracias por su paciencia. Nuestro equipo aún no ha podido responder en el chat, así que una persona se pondrá en contacto con usted por correo electrónico o por teléfono lo antes posible, con los datos que nos ha dejado. Mientras tanto, sigo respondiendo con gusto a sus preguntas.",
            "pl" to "Dziękuję za cierpliwość. Nasz zespół nie zdążył jeszcze odpowiedzieć na czacie, dlatego wkrótce skontaktuje się z Tobą osoba z zespołu e-mailem lub telefonicznie, korzystając z podanych przez Ciebie danych. W międzyczasie chętnie dalej odpowiadam na Twoje pytania.",
            "nl" to "Bedankt voor uw geduld. Ons team kon nog niet in de chat antwoorden, daarom neemt zo snel mogelijk een medewerker per e-mail of telefoon contact met u op via de gegevens die u heeft achtergelaten. Intussen beantwoord ik graag uw vragen.",
        )

    private val AI_PAUSED =
        mapOf(
            "en" to "Our AI assistant is paused for a few minutes. Please leave your email address or phone number here and a person from our team will get back to you.",
            "hr" to "Naša AI asistentica je na nekoliko minuta isključena. Ostavite ovdje svoju email adresu ili broj telefona i javit će vam se osoba iz našeg tima.",
            "fr" to "Notre assistante IA est en pause pour quelques minutes. Laissez ici votre adresse e-mail ou votre numéro de téléphone et une personne de notre équipe vous recontactera.",
            "de" to "Unsere KI-Assistentin ist für einige Minuten pausiert. Bitte hinterlassen Sie hier Ihre E-Mail-Adresse oder Telefonnummer, dann meldet sich eine Person aus unserem Team bei Ihnen.",
            "pt" to "A nossa assistente de IA está em pausa durante alguns minutos. Deixe aqui o seu e-mail ou número de telefone e uma pessoa da nossa equipa entrará em contacto consigo.",
            "it" to "La nostra assistente IA è in pausa per qualche minuto. Lasci qui il suo indirizzo e-mail o numero di telefono e una persona del nostro team la ricontatterà.",
            "es" to "Nuestra asistente de IA está en pausa unos minutos. Deje aquí su correo electrónico o su número de teléfono y una persona de nuestro equipo se pondrá en contacto con usted.",
            "pl" to "Nasza asystentka AI jest wstrzymana na kilka minut. Zostaw tutaj swój adres e-mail lub numer telefonu, a ktoś z naszego zespołu się z Tobą skontaktuje.",
            "nl" to "Onze AI-assistent is een paar minuten gepauzeerd. Laat hier uw e-mailadres of telefoonnummer achter, dan neemt iemand van ons team contact met u op.",
        )
}
