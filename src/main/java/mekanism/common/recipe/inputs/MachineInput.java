package mekanism.common.recipe.inputs;

public abstract class MachineInput
{
	public abstract int hashIngredients();

	/**
	 * Test equality to another input.
	 * This should return true if the input matches this one,
	 * IGNORING AMOUNTS.
	 * Allows usage of HashMap optimisation to get recipes.
	 * @param other
	 * @return
	 */
	public abstract boolean testEquality(MachineInput other);

	@Override
	public int hashCode()
	{
		return hashIngredients();
	}

	@Override
	public boolean equals(Object other)
	{
		if(other instanceof MachineInput)
		{
			return testEquality((MachineInput)other);
		}
		return false;
	}


}