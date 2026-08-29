type CheckboxGroupProps<T extends string> = {
    values: readonly T[]
    selected: T[]
    onChange: (
        values: T[],
    ) => void
    getLabel?: (
        value: T,
    ) => string
}

export function CheckboxGroup<T extends string>({
    values,
    selected,
    onChange,
    getLabel = (value) => value,
}: CheckboxGroupProps<T>) {
    return (
        <div className="models-form__checks">
            {values.map((value) => (
                <label key={value}>
                    <input
                        type="checkbox"
                        checked={selected.includes(value)}
                        onChange={(event) => {
                            onChange(
                                event.target.checked
                                    ? [
                                        ...selected,
                                        value,
                                    ]
                                    : selected.filter(
                                        (item) =>
                                            item !== value,
                                    ),
                            )
                        }}
                    />
                    {getLabel(value)}
                </label>
            ))}
        </div>
    )
}

type DecimalInputProps = {
    label: string
    value: string
    placeholder?: string
    onChange: (
        value: string,
    ) => void
}

export function DecimalInput({
    label,
    value,
    placeholder,
    onChange,
}: DecimalInputProps) {
    return (
        <label>
            {label}
            <input
                inputMode="decimal"
                value={value}
                placeholder={placeholder}
                onChange={(event) => {
                    onChange(
                        event.target.value,
                    )
                }}
            />
        </label>
    )
}
